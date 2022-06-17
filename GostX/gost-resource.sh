#!/bin/bash
set -euo pipefail

# Download and unpack gost into ${PRODUCT_NAME}.app/Contents/Resources
GOST_VERSION="2.11.2"
GOST_DIST_URL="https://github.com/ginuerzh/gost/releases/download"
GOST_ARM64_TARBALL_URL="${GOST_DIST_URL}/v${GOST_VERSION}/gost-darwin-arm64-${GOST_VERSION}.gz"
GOST_AMD64_TARBALL_URL="${GOST_DIST_URL}/v${GOST_VERSION}/gost-darwin-amd64-${GOST_VERSION}.gz"

CURL_ARGS="--connect-timeout 5 --max-time 10 --retry 5 --retry-delay 3 --retry-max-time 60"
DL_DIR="${BUILT_PRODUCTS_DIR}/dl"
GOST_ARM64_TARBALL="${DL_DIR}/gost-arm64-${GOST_VERSION}.gz"
GOST_AMD64_TARBALL="${DL_DIR}/gost-amd64-${GOST_VERSION}.gz"
APP_RESOURCES_DIR="${BUILT_PRODUCTS_DIR}/${PRODUCT_NAME}.app/Contents/Resources"
TAR_DIR="${APP_RESOURCES_DIR}/gost"

# Download gost tarball
if [ -f "${GOST_ARM64_TARBALL}" ]; then
    echo "-- Gost arm64 already downloaded"
    echo "   > ${GOST_ARM64_TARBALL}"
else
    echo "-- Downloading gost arm64"
    echo "   From > ${GOST_ARM64_TARBALL_URL}"
    echo "     To > ${GOST_ARM64_TARBALL}"

    mkdir -p "${DL_DIR}"
    curl ${CURL_ARGS} -s -L -o ${GOST_ARM64_TARBALL} ${GOST_ARM64_TARBALL_URL}
fi

# Download gost tarball
if [ -f "${GOST_AMD64_TARBALL}" ]; then
    echo "-- Gost amd64 already downloaded"
    echo "   > ${GOST_AMD64_TARBALL}"
else
    echo "-- Downloading gost amd64"
    echo "   From > ${GOST_AMD64_TARBALL_URL}"
    echo "     To > ${GOST_AMD64_TARBALL}"

    mkdir -p "${DL_DIR}"
    curl ${CURL_ARGS} -s -L -o ${GOST_AMD64_TARBALL} ${GOST_AMD64_TARBALL_URL}
fi

# Unpack to .app Resources folder
if [ -d "${TAR_DIR}/gost" ]; then
    echo "-- Gost already unpacked"
    echo "   > ${TAR_DIR}"
else
    echo "-- Unpacking gost"
    echo "   > ${TAR_DIR}"
    mkdir -p "${TAR_DIR}"
    gzip -d -f -k -N "${GOST_AMD64_TARBALL}"
    gzip -d -f -k -N "${GOST_ARM64_TARBALL}"
    lipo -create -output ${DL_DIR}/gost ${DL_DIR}/gost-darwin-amd64 ${DL_DIR}/gost-darwin-arm64
    mv ${DL_DIR}/gost ${TAR_DIR}/
    chmod +x ${TAR_DIR}/gost
fi
