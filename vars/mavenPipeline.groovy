/*
Copyright 2021  Simon Arlott

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

import uk.uuid.jenkins.pipeline.Cron
import uk.uuid.jenkins.pipeline.Email

def maven_matrix_main() {
	return JAVA == "17"
}

def maven_matrix_labels() {
	def labels = ["Java"]

	if (PARAMS.requiresDisplay) {
		labels.add("Xvfb")
	}

	return labels.join(" && ")
}

def display_wrapper(display, body) {
	if (display) {
		wrap([$class: "Xvfb", displayName: EXECUTOR_NUMBER as Integer, screen: "1920x1080x24", debug: true, timeout: 5]) {
			body()
		}
	} else {
		body()
	}
}

def call(body) {
	PARAMS = [
		deploySnapshot: false,
		hasTests: true,
		requiresDisplay: false,
	]
	if (body) {
		body.resolveStrategy = Closure.DELEGATE_FIRST
		body.delegate = PARAMS
		body()
	}
	echo("Params: ${PARAMS}")

	pipeline {
		agent none
		triggers {
			cron("${Cron.schedule(this)}")
		}
		environment {
			TMPDIR = "${WORKSPACE_TMP}"
		}
		stages {
			stage("Maven") {
				matrix {
					axes {
						axis {
							name "JAVA"
							values "8", "11", "17"
						}
						axis {
							name "MAVEN"
							values "3"
						}
					}
					agent {
						label maven_matrix_labels()
					}
					tools {
						jdk JAVA
					}
					stages {
						stage("Checkout") {
							steps {
								sh "git clean -fdx"
								sh "git submodule sync --recursive"
								sh "git submodule update --init --recursive --depth 1"
							}
						}
						stage("Verify") {
							when {
								expression { !maven_matrix_main() }
							}
							steps {
								withMaven(maven: MAVEN, publisherStrategy: "EXPLICIT", traceability: false) {
									display_wrapper(PARAMS.requiresDisplay, {
										sh "mvn verify"
									})
								}
							}
						}
						stage("Verify/Deploy & Site") {
							when {
								expression { maven_matrix_main() }
							}
							steps {
								withMaven(maven: MAVEN, publisherStrategy: "EXPLICIT", traceability: false) {
									script {
										def version = sh script: "mvn help:evaluate -Dexpression=project.version -q -DforceStdout", returnStdout: true
										def goal = "verify"

										if (PARAMS.deploySnapshot && version.endsWith("-SNAPSHOT")) {
											goal = "deploy"
										}

										display_wrapper(PARAMS.requiresDisplay, {
											sh "mvn ${goal} site"
										})
									}
								}
							}
							post {
								always {
									script {
										if (PARAMS.hasTests) {
											junit testResults: "target/surefire-reports/TEST-*.xml"
											jacoco execPattern: "target/jacoco.exec"
										}
									}

									recordIssues enabledForFailure: true, tools: [
										mavenConsole(),
										java(),
										javaDoc(),
									] + (PARAMS.hasTests ? [spotBugs()] : [])
								}
								success {
									script {
										withMaven(maven: MAVEN, publisherStrategy: "EXPLICIT", traceability: false) {
											def artifactId = sh script: "mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout", returnStdout: true

											publishHTML([
												allowMissing: false,
												alwaysLinkToLastBuild: false,
												keepAll: false,
												reportName: "Maven Site",
												reportDir: "target/site/",
												reportTitles: artifactId,
												reportFiles: "index.html",
											])
										}
									}
								}
							}
						}
					}
					post {
						cleanup {
							cleanWs()
						}
					}
				}
			}
		}
		post {
			always {
				script {
					Email.send(this)
				}
			}
		}
	}
}
