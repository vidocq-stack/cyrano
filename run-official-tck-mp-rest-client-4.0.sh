#!/usr/bin/env bash
# shellcheck shell=bash
#
# Lance la suite TCK officielle MicroProfile Rest Client 4.0
# (org.eclipse.microprofile.rest.client:microprofile-rest-client-tck:4.0)
# contre l'implémentation Cyrano.
#
# Modes :
#   ./run-official-tck-mp-rest-client-4.0.sh                 # smoke test (sans Arquillian)
#   ./run-official-tck-mp-rest-client-4.0.sh all             # suite complète (Arquillian + container Cyrano)
#   ./run-official-tck-mp-rest-client-4.0.sh -Dtest=Foo      # test ciblé via le profil tck-official
#
# Comportement :
#   1. Installe en local (./mvnw install -DskipTests) cyrano-api/cyrano-core/cyrano-cdi-vauban
#   2. Invoque ./mvnw -Ptck,<profile> -pl cyrano-tck test [args...]
#      (cyrano-tck est in-reactor, activé par le profil Maven `tck` —
#      harmonisation TCK, même pattern que les runners vidocq-runtime-tck-*)
#   3. Génère target/tck-report.txt avec le résumé PASS/FAIL/SKIP
#
# Vérification de la disponibilité du TCK sur Maven Central :
#   mvn dependency:get -Dartifact=org.eclipse.microprofile.rest.client:microprofile-rest-client-tck:4.0
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TCK_DIR="${ROOT_DIR}/cyrano-tck"
REPORT_FILE="${TCK_DIR}/target/tck-report.txt"

mode="${1:-smoke}"
shift || true

case "${mode}" in
    smoke)
        profile="smoke"
        echo "==> Mode : SMOKE (CyranoTckSmokeTest, hors Arquillian)"
        ;;
    all)
        profile="tck-official"
        echo "==> Mode : ALL (suite officielle MicroProfile Rest Client 4.0 — TestNG/Arquillian)"
        ;;
    -Dtest=*)
        profile="tck-official"
        set -- "${mode}" "$@"
        echo "==> Mode : ciblé (${mode}) avec profil tck-official"
        ;;
    *)
        echo "Usage : $0 [smoke|all|-Dtest=NomDuTest]" >&2
        exit 64
        ;;
esac

echo "==> Étape 1/2 : install local des artefacts Cyrano (./mvnw install -DskipTests)"
( cd "${ROOT_DIR}" && ./mvnw -ntp -pl cyrano-api,cyrano-core,cyrano-cdi-vauban -am install -DskipTests )

echo "==> Étape 2/2 : exécution Maven sur cyrano-tck in-reactor (profils=tck,${profile})"
mkdir -p "${TCK_DIR}/target"

MVN="${ROOT_DIR}/mvnw"

set +e
( cd "${ROOT_DIR}" && "${MVN}" -ntp -P"tck,${profile}" -pl cyrano-tck test "$@" ) \
    | tee "${REPORT_FILE}.raw"
status=$?
set -e

echo "==> Génération du rapport : ${REPORT_FILE}"
summary_line="$(grep 'Tests run:' "${REPORT_FILE}.raw" | tail -n 1 | perl -pe 's/\e\[[0-9;]*[A-Za-z]//g' || true)"
tests_run=""
tests_failures=""
tests_errors=""
tests_skipped=""
tests_passed=""
if [[ "${summary_line}" =~ ^(\[[A-Z]+\]\ )?Tests\ run:\ ([0-9]+),\ Failures:\ ([0-9]+),\ Errors:\ ([0-9]+),\ Skipped:\ ([0-9]+) ]]; then
    tests_run="${BASH_REMATCH[2]}"
    tests_failures="${BASH_REMATCH[3]}"
    tests_errors="${BASH_REMATCH[4]}"
    tests_skipped="${BASH_REMATCH[5]}"
    tests_passed=$((tests_run - tests_failures - tests_errors - tests_skipped))
fi
{
    echo "# Cyrano TCK report"
    echo "# Généré le $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# Profile : ${profile}"
    echo "# Args    : $*"
    echo
    if [ -n "${summary_line}" ]; then
        echo "# Résumé : ${summary_line}"
    fi
    if [ -n "${tests_passed}" ]; then
        echo "# Tests réussis : ${tests_passed}/${tests_run}"
        echo "# Détails       : failures=${tests_failures}, errors=${tests_errors}, skipped=${tests_skipped}"
    fi
    grep -E "^\[INFO\] Tests run:|^Tests run:" "${REPORT_FILE}.raw" || true
    echo
    if [ ${status} -eq 0 ]; then
        echo "RESULT : PASS"
    else
        echo "RESULT : FAIL (exit ${status})"
    fi
} > "${REPORT_FILE}"

cat "${REPORT_FILE}"
exit ${status}

