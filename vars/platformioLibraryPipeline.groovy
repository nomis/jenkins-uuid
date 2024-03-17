/*
Copyright 2021-2022  Simon Arlott

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

def call() {
	pipeline {
		agent {
			label "Linux && Python && doxygen && graphviz"
		}
		triggers {
			cron("${Cron.schedule(this)}")
		}
		options {
			disableConcurrentBuilds()
		}
		environment {
			CI = "true"
			TMPDIR = "${WORKSPACE_TMP}"
			PIPENV_VENV_IN_PROJECT = "1"
			PIPENV_SKIP_LOCK = "1"
		}
		stages {
			stage("Checkout") {
				steps {
					sh "git clean -fdx"
					sh "git fetch --tags"
					sh "git submodule sync"
					sh "git submodule update --init --depth 1"

					script {
						def PIO_LIBRARY = readJSON file: "library.json"
						echo "Library: ${PIO_LIBRARY.name} v${PIO_LIBRARY.version} (${PIO_LIBRARY.description})"
					}
				}
			}
			stage("Prepare") {
				steps {
					sh "pipenv install platformio Sphinx"
					sh "pipenv graph"
					lock("NODE=${NODE_NAME} APP=platformio") {
						sh "pipenv run platformio update"
					}
				}
			}
			stage("Test") {
				steps {
					sh "pipenv run make -C test"
					sh "git diff --exit-code"
				}
			}
			stage("Docs") {
				steps {
					sh "pipenv run make -C docs html linkcheck"
				}
			}
			stage("Registry") {
				steps {
					sh "pipenv run make -C test registry"
				}
			}
		}
		post {
			always {
				script {
					Email.send(this)
				}
			}
			success {
				script {
					script {
						def PIO_LIBRARY = readJSON file: "library.json"

						publishHTML([
							allowMissing: false,
							alwaysLinkToLastBuild: false,
							keepAll: false,
							reportName: "Documentation",
							reportDir: "docs/build/html/",
							reportTitles: PIO_LIBRARY.name,
							reportFiles: "index.html",
						])
					}
				}
			}
			cleanup {
				cleanWs()
			}
		}
	}
}
