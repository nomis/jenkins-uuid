FROM debian:testing
ENV DEBIAN_FRONTEND=noninteractive

RUN rm /etc/apt/apt.conf.d/docker-clean
RUN echo 'APT::Keep-Downloaded-Packages "true";' >/etc/apt/apt.conf.d/keep-downloaded-packages

RUN \
	--mount=type=cache,sharing=locked,target=/var/cache/apt,id=debian-testing-var-cache-apt \
	--mount=type=cache,sharing=locked,target=/var/lib/apt,id=debian-testing-var-lib-apt \
	apt-get update
RUN \
	--mount=type=cache,sharing=locked,target=/var/cache/apt,id=debian-testing-var-cache-apt \
	--mount=type=cache,sharing=locked,target=/var/lib/apt,id=debian-testing-var-lib-apt \
	apt-get install -y build-essential git procps python3-pip
RUN \
	--mount=type=cache,sharing=locked,target=/var/cache/apt,id=debian-testing-var-cache-apt \
	--mount=type=cache,sharing=locked,target=/var/lib/apt,id=debian-testing-var-lib-apt \
	apt-get install -y wget doxygen graphviz

ARG PIO_VERSION=
ADD https://pypi.org/pypi/platformio/json /dev/shm/platformio.json
RUN \
	--mount=type=cache,target=/root/.cache/pip,sharing=locked,id=debian-testing-root-cache-pip \
	python3 --version && \
	pip3 --version && \
	pip3 install --break-system-packages -U "platformio$PIO_VERSION"
ENV PIO_VERSION=

ADD https://pypi.org/pypi/Sphinx/json /dev/shm/Sphinx.json
RUN \
	--mount=type=cache,target=/root/.cache/pip,sharing=locked,id=debian-testing-root-cache-pip \
	python3 --version && \
	pip3 --version && \
	pip3 install --break-system-packages -U Sphinx

ARG APT_PACKAGES=
RUN \
	--mount=type=cache,sharing=locked,target=/var/cache/apt,id=debian-testing-var-cache-apt \
	--mount=type=cache,sharing=locked,target=/var/lib/apt,id=debian-testing-var-lib-apt \
	[ -z "$APT_PACKAGES" ] || apt-get install -y $APT_PACKAGES
ENV APT_PACKAGES=

COPY requirements.txt /root/requirements.txt
RUN \
	--mount=type=cache,target=/root/.cache/pip,sharing=locked,id=debian-testing-root-cache-pip \
	python3 --version && \
	pip3 --version && \
	pip3 install --break-system-packages -U -r /root/requirements.txt

ENV PLATFORMIO_CACHE_DIR=/var/cache/platformio/cache
ENV PLATFORMIO_PACKAGES_DIR=/var/cache/platformio/packages
ENV PLATFORMIO_PLATFORMS_DIR=/var/cache/platformio/platforms

# Jenkins always runs commands in the container as its own UID/GID, which has no
# access to anything outside the container. It also does this for the entrypoint
# so a setuid executable is required for this hack.
#
# This file should not have a fixed UID/GID for Jenkins so determine the UID/GID
# at runtime when executing the entrypoint and use the setuid shell to set up
# a user and fix filesystem permissions on startup. We can't use sudo (which is
# just extra overhead) because that requires an entry in /etc/passwd.
RUN chmod u+s /bin/dash
RUN <<EOF
	cat >/entrypoint.sh <<EOT
#!/bin/dash
if [ -u /bin/dash ]; then
	/bin/dash -pc "groupadd -g \$(id -g) user" || exit 1
	/bin/dash -pc "useradd -u \$(id -u) -g \$(id -g) -d /home/user -s /bin/bash user" || exit 1
	/bin/dash -pc "install -d -m 0700 -o user -g user /home/user /home/user/.cache /home/user/.cache/pip /home/user/.cache/pipenv /var/cache/platformio" || exit 1
	/bin/dash -pc "chmod u-s /bin/dash" || exit 1
fi
exec "\$@"
EOT
	chmod +x /entrypoint.sh
EOF

ENTRYPOINT ["/entrypoint.sh"]
