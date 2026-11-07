ARG CLN_IMAGE=elementsproject/lightningd:v25.12.1
FROM ${CLN_IMAGE} AS cln-nip47

ARG CLN_NIP47_VERSION=v0.2.2
# Set by BuildKit to the build platform (arm64 on Apple silicon, amd64 in CI).
ARG TARGETARCH

USER root

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl tar; \
    case "${TARGETARCH}" in \
      amd64) asset_arch="x86_64-linux-gnu" ;; \
      arm64) asset_arch="aarch64-linux-gnu" ;; \
      arm) asset_arch="armv7-linux-gnueabihf" ;; \
      *) echo "Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL \
      "https://github.com/daywalker90/cln-nip47/releases/download/${CLN_NIP47_VERSION}/cln-nip47-${CLN_NIP47_VERSION}-${asset_arch}.tar.gz" \
      -o /tmp/cln-nip47.tar.gz; \
    tar -xzf /tmp/cln-nip47.tar.gz -C /tmp; \
    install -m 0755 /tmp/cln-nip47 /usr/local/bin/cln-nip47; \
    rm -rf /tmp/cln-nip47 /tmp/cln-nip47.tar.gz /var/lib/apt/lists/*

FROM cln-nip47 AS helper

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends python3; \
    rm -rf /var/lib/apt/lists/*

COPY helper/server.py /usr/local/bin/rayl-nwc-helper

EXPOSE 8080
ENTRYPOINT ["python3", "/usr/local/bin/rayl-nwc-helper"]
