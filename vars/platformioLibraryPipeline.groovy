/*
Copyright 2021-2022,2024,2026  Simon Arlott

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
				/*
				 * Run this stage on a Docker node to create the Dockerfile in
				 * the workspace and then reuse the node for the container.
				 */
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
						args "--mount source=var-cache-platformio${(config.pio_version ? "-${config.pio_version}" : "")},target=/var/cache/platformio"
						reuseNode true
					}
				}
				stages {
					stage("Checkout") {
						steps {
							sh "git clean -ffdx"
							sh "git fetch --tags"
							sh "git submodule sync"
							sh "git submodule update --init --depth 1"

							script {
								def PIO_LIBRARY = readJSON file: "library.json"
								echo "Library: ${PIO_LIBRARY.name} v${PIO_LIBRARY.version} (${PIO_LIBRARY.description})"
							}
						}
					}
					stage("Test") {
						steps {
							sh "platformio --version"
							sh "pio settings set check_prune_system_threshold 0"
							sh "make -C test"
							sh "git diff --exit-code"
						}
					}
					stage("Docs") {
						steps {
							sh "doxygen --version"
							sh "make -C docs html linkcheck"
						}
					}
					stage("Publish") {
						when {
							expression { config.publish }
						}
						steps {
							script {
								def PIO_LIBRARY = readJSON file: "library.json"
								def PIO_VERSIONS = sh (
									script: "platformio pkg show \"${config.publish.owner}/${PIO_LIBRARY.name}\" | grep -A 1000000 -E ^Version | tail -n +3 | grep -vE '^\$' | awk '{print \$1}' | sort -",
									returnStdout: true
								).tokenize('\n')
								def ALL_TAGS = sh (
									script: "git tag | sort -n | grep -E '^[0-9]+\\.[0-9]+\\.[0-9]+\$'",
									returnStdout: true
								).tokenize('\n')
								def PUBLISH_TAGS = (ALL_TAGS.toSet() - PIO_VERSIONS.toSet()).toList().sort()

								echo "Tags: ${ALL_TAGS}"
								echo "Registry: ${PIO_VERSIONS}"
								echo "Publish: ${PUBLISH_TAGS}"

								if (PUBLISH_TAGS) {
									withCredentials([
											usernamePassword(credentialsId: "platformio",
												usernameVariable: "PIO_USER",
												passwordVariable: "PIO_PASS")
											]) {
										sh (
											script: 'pio account login -u "$PIO_USER" -p "$PIO_PASS"',
											returnStatus: true
										)
										sh 'pio account show'
									}
								}

								for (tag in PUBLISH_TAGS) {
									sh "git worktree add publish-$tag $tag"
									dir("publish-$tag") {
										sh "pio pkg publish --owner \"${config.publish.owner}\" --no-interactive"
									}
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
