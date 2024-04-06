/*
Copyright 2022-2024  Simon Arlott

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
			PLATFORMIO_CORE_DIR = "${WORKSPACE_TMP}/.platformio"
		}
		stages {
			stage("Prepare") {
				steps {
					echo "Config: ${config}"
					dir(WORKSPACE_TMP) {
						writeFile(file: "Dockerfile", text:libraryResource("platformio.Dockerfile"))
						sh "rm -f requirements.txt"
						sh "touch requirements.txt"
					}
					sh "[ ! -e .uuid-uk/requirements.txt ] || cat .uuid-uk/requirements.txt > \"${WORKSPACE_TMP}/requirements.txt\""
				}
			}
			stage("Docker") {
				agent {
					dockerfile {
						dir WORKSPACE_TMP
						filename "Dockerfile"
						additionalBuildArgs (
							"--build-arg PIO_VERSION=\"${(config.pio_version ? "==${config.pio_version}" : "")}\""
							+ " --build-arg APT_PACKAGES=\"${(config.apt_packages ?: []).join(" ")}\""
						)
						args (
							"--mount source=var-cache-platformio,target=/var/cache/platformio"
							+ " --mount source=user-cache-pip,target=/home/user/.cache/pip"
							+ " --mount source=user-cache-pipenv,target=/home/user/.cache/pipenv"
						)
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
							sh "platformio --version"
							sh "make"
							sh "git diff --exit-code"
						}
					}
					stage("Build (local)") {
						when {
							expression { fileExists("pio_local.ini.example") && !fileExists("pio_local.ini") }
						}
						steps {
							sh "cp pio_local.ini.example pio_local.ini"
							sh "platformio --version"
							sh "make"
							sh "git diff --exit-code"
						}
					}
					stage("Test") {
						when {
							expression { fileExists("test") }
						}
						steps {
							sh "platformio --version"
							sh "make -C test"
							sh "git diff --exit-code"
						}
					}
					stage("Docs") {
						when {
							expression { fileExists("docs") }
						}
						steps {
							sh "make -C docs html linkcheck"
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
