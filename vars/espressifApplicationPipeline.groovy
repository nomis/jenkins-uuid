/*
Copyright 2024  Simon Arlott

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

def call(body) {
	config = [:]
	if (body) {
		body.resolveStrategy = Closure.DELEGATE_FIRST
		body.delegate = config
		body()
	}

	pipeline {
		agent none
		triggers {
			cron("${Cron.schedule(this)}")
		}
		options {
			disableConcurrentBuilds()
		}
		environment {
			CI = "true"
			TMPDIR = "${WORKSPACE_TMP}"
		}
		stages {
			stage("Application") {
				matrix {
					when {
						expression { TARGET in config.idf_targets }
					}
					axes {
						axis {
							name "TARGET"
							values "esp32", "esp32s2", "esp32c3", "esp32s3", "esp32c2", "esp32c6", "esp32h2"
						}
					}
					stages {
						/*
						 * Need to run this stage on "agent none" so that the
						 * "when" is evaluated before running on a node.
						 */
						stage("Target") {
							agent {
								label "Linux && Docker"
							}
							stages {
								/*
								 * Run this stage on a Docker node to create the
								 * Dockerfile in the workspace and then reuse
								 * the node for the container.
								 */
								stage("Prepare") {
									steps {
										echo "Config: ${config}"
										dir(WORKSPACE_TMP) {
											writeFile(file: "Dockerfile", text:libraryResource("espressif.Dockerfile"))
										}
									}
								}
								stage("Docker") {
									agent {
										dockerfile {
											dir WORKSPACE_TMP
											filename "Dockerfile"
											args "--mount source=user-cache-espressif,target=/home/user/.cache/Espressif"
											reuseNode true
										}
									}
									stages {
										stage("Checkout") {
											steps {
												sh "git clean -fdx"
												sh "git submodule sync"
												sh "git submodule update --init --depth 1"
											}
										}
										stage("Build") {
											steps {
												sh """
													set +x
													. "\${IDF_PATH}/export.sh" || exit 1
													set -x
													idf.py --version || exit 1
													idf.py set-target ${TARGET} || exit 1
													idf.py build || exit 1
												"""
												sh "git diff dependencies.lock*"
												sh "git restore dependencies.lock*"
												sh "git diff --exit-code"
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
