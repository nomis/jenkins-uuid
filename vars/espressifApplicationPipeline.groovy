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
		agent {
			label "Linux && Docker"
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
		}
		stages {
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
								. "\${IDF_PATH}/export.sh"
								set -x
								idf.py --version
								make target
								make build
							"""
							sh "git diff --exit-code"
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
			cleanup {
				cleanWs()
			}
		}
	}
}
