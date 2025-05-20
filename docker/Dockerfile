# Stage 1: Unpack your local snapshot dist
FROM ubuntu:22.04 AS bk-dist
ARG BK_VERSION=4.17.2-SNAPSHOT
ARG DISTRO_TARBALL=bookkeeper-server-${BK_VERSION}-bin.tar.gz
COPY bookkeeper-dist/server/target/${DISTRO_TARBALL} /opt/
WORKDIR /opt
RUN tar -xzf ${DISTRO_TARBALL} \
 && mv bookkeeper-server-${BK_VERSION} bookkeeper \
 && rm ${DISTRO_TARBALL}

# Stage 2: (Optional) jlink custom JRE for size
FROM eclipse-temurin:17 AS jre-build
RUN $JAVA_HOME/bin/jlink \
     --add-modules ALL-MODULE-PATH \
     --strip-debug \
     --no-man-pages \
     --no-header-files \
     --compress=2 \
     --output /javaruntime \
 && echo networkaddress.cache.ttl=1 >> /javaruntime/conf/security/java.security \
 && echo networkaddress.cache.negative.ttl=1 >> /javaruntime/conf/security/java.security

# Stage 3: Final runtime image
FROM ubuntu:22.04
ARG BK_VERSION=4.17.2-SNAPSHOT
ENV DEBIAN_FRONTEND=noninteractive \
    BK_HOME=/opt/bookkeeper \
    BOOKIE_PORT=3181 \
    BOOKIE_HTTP_PORT=4181 \
    BK_USER=bookkeeper
EXPOSE ${BOOKIE_PORT} ${BOOKIE_HTTP_PORT}

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      openjdk-17-jdk-headless \
      python3-pip \
      wget \
      sudo \
 && ln -s /usr/bin/python3 /usr/bin/python \  
 && rm -rf /var/lib/apt/lists/* \
 && pip3 install zk-shell

ENV JAVA_HOME=/opt/java/openjdk
COPY --from=jre-build /javaruntime $JAVA_HOME
ENV PATH="$JAVA_HOME/bin:$PATH"

COPY --from=bk-dist /opt/bookkeeper ${BK_HOME}/
COPY docker/scripts ${BK_HOME}/scripts

RUN useradd --uid 10000 --gid root --home-dir ${BK_HOME} --shell /bin/bash --no-create-home bookkeeper \
 && mkdir -p ${BK_HOME}/conf ${BK_HOME}/data/journal ${BK_HOME}/data/ledgers ${BK_HOME}/logs \
 && chown -R bookkeeper:root ${BK_HOME} \
 && chmod -R ug+rwX ${BK_HOME}/conf ${BK_HOME}/data ${BK_HOME}/logs \
 && chmod -R g+rX ${BK_HOME}/scripts ${BK_HOME}/bin \
 && chmod -R o+rX ${BK_HOME}

WORKDIR ${BK_HOME}
USER bookkeeper
ENTRYPOINT ["/bin/bash", "/opt/bookkeeper/scripts/entrypoint.sh"]
CMD ["bookie"]
HEALTHCHECK --interval=10s --timeout=60s \
  CMD ["/bin/bash", "/opt/bookkeeper/scripts/healthcheck.sh"]