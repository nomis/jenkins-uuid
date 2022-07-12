/*
Copyright 2022  Simon Arlott

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
			label "Linux && Python"
		}
		triggers {
			cron("${Cron.schedule(this)}")
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
				}
			}
			stage("Prepare") {
				steps {
					script {
						sh "pipenv install platformio"
						sh "pipenv graph"
						lock("NODE=${NODE_NAME} APP=platformio") {
							sh "pipenv run platformio update"
						}
						if (fileExists("pio_local.ini.example")) {
							sh "cp pio_local.ini.example pio_local.ini"
						}
					}
				}
			}
			stage("Build") {
				steps {
					sh "pipenv run make"
					sh "git diff --exit-code"
				}
			}
			stage("Docs") {
				when {
					expression { fileExists("docs") }
				}
				steps {
					sh "pipenv run make -C docs html linkcheck"
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
