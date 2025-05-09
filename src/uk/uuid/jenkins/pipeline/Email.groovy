/*
Copyright 2021,2025  Simon Arlott

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

package uk.uuid.jenkins.pipeline

class Email implements Serializable {
	static def send(steps) {
		steps.echo("Current Build: ${steps.currentBuild.currentResult}")

		if (steps.env.CHANGE_URL) {
			steps.echo("Change URL: ${steps.env.CHANGE_URL}")
		}

		def previousBuild = steps.currentBuild.previousBuild
		if (previousBuild) {
			steps.echo("Previous Build: ${previousBuild.result}")

			if (steps.currentBuild.currentResult == "SUCCESS"
					&& previousBuild.result == "SUCCESS"
					&& steps.env.CHANGE_URL == null) {
				"/home/simon/bin/ci-success ${steps.JOB_NAME}#${steps.BUILD_NUMBER} ${steps.BUILD_URL}".execute()
				return
			}
		}

		def subject = "${steps.currentBuild.currentResult}: ${steps.JOB_NAME}#${steps.BUILD_NUMBER}"

		if (steps.env.GIT_COMMIT) {
			subject += " (${steps.env.GIT_COMMIT})"
		}

		def body = "${steps.BUILD_URL}\n"

		if (steps.env.CHANGE_URL) {
			body += "\n"
			body += "Change: ${steps.env.CHANGE_TITLE}\n"
			body += "  URL: ${steps.env.CHANGE_URL}\n"
			body += "  From: ${steps.env.CHANGE_AUTHOR_DISPLAY_NAME} (${steps.env.CHANGE_AUTHOR})\n"
			body += "  Source: ${steps.env.CHANGE_BRANCH}\n"
			body += "  Target: ${steps.env.CHANGE_TARGET}\n"
		}

		steps.emailext([
					to: "simon",
					subject: subject,
					body: body,
				])

		if (steps.currentBuild.currentResult == "SUCCESS") {
			"/home/simon/bin/ci-success ${steps.JOB_NAME}#${steps.BUILD_NUMBER} ${steps.BUILD_URL}".execute()
		} else if (steps.currentBuild.currentResult == "FAILURE") {
			"/home/simon/bin/ci-failure ${steps.JOB_NAME}#${steps.BUILD_NUMBER} ${steps.BUILD_URL}".execute()
		}
	}
}
