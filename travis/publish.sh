#!/usr/bin/env bash
#
# Copyright 2016-2020 The OpenZipkin Authors
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.
#

set -euxo pipefail

build_started_by_tag() {
  if [ "${TRAVIS_TAG}" == "" ]; then
    echo "[Publishing] This build was not started by a tag, publishing snapshot"
    return 1
  else
    echo "[Publishing] This build was started by the tag ${TRAVIS_TAG}, publishing release"
    return 0
  fi
}

is_pull_request() {
  if [ "${TRAVIS_PULL_REQUEST}" != "false" ]; then
    echo "[Not Publishing] This is a Pull Request"
    return 0
  else
    echo "[Publishing] This is not a Pull Request"
    return 1
  fi
}

is_travis_branch_master() {
  if [ "${TRAVIS_BRANCH}" = master ]; then
    echo "[Publishing] Travis branch is master"
    return 0
  else
    echo "[Not Publishing] Travis branch is not master"
    return 1
  fi
}

check_travis_branch_equals_travis_tag() {
  #Weird comparison comparing branch to tag because when you 'git push --tags'
  #the branch somehow becomes the tag value
  #github issue: https://github.com/travis-ci/travis-ci/issues/1675
  if [ "${TRAVIS_BRANCH}" != "${TRAVIS_TAG}" ]; then
    echo "Travis branch does not equal Travis tag, which it should, bailing out."
    echo "  github issue: https://github.com/travis-ci/travis-ci/issues/1675"
    exit 1
  else
    echo "[Publishing] Branch (${TRAVIS_BRANCH}) same as Tag (${TRAVIS_TAG})"
  fi
}

check_release_tag() {
    tag="${TRAVIS_TAG}"
    if [[ "$tag" =~ ^[[:digit:]]+\.[[:digit:]]+\.[[:digit:]]+$ ]]; then
        echo "Build started by version tag $tag. During the release process tags like this"
        echo "are created by the 'release' Maven plugin. Nothing to do here."
        exit 0
    elif [[ ! "$tag" =~ ^release-[[:digit:]]+\.[[:digit:]]+\.[[:digit:]]+$ ]]; then
        echo "You must specify a tag of the format 'release-0.0.0' to release this project."
        echo "The provided tag ${tag} doesn't match that. Aborting."
        exit 1
    fi
}

print_project_version() {
  # Cache as help:evaluate is not quick
  export POM_VERSION=${POM_VERSION:-$(mvn help:evaluate -N -Dexpression=project.version -q -DforceStdout)}
  echo "${POM_VERSION}"
}

is_release_version() {
  project_version="$(print_project_version)"
  if [[ "$project_version" =~ ^[[:digit:]]+\.[[:digit:]]+\.[[:digit:]]+$ ]]; then
    echo "Build started by release commit $project_version. Will synchronize to maven central."
    return 0
  else
    return 1
  fi
}

release_version() {
  echo "${TRAVIS_TAG}" | sed 's/^release-//'
}

safe_checkout_master() {
  # We need to be on a branch for release:perform to be able to create commits, and we want that branch to be master.
  # But we also want to make sure that we build and release exactly the tagged version, so we verify that the remote
  # master is where our tag is.
  git checkout -B master
  git fetch origin master:origin/master
  commit_local_master="$(git show --pretty='format:%H' master)"
  commit_remote_master="$(git show --pretty='format:%H' origin/master)"
  if [ "$commit_local_master" != "$commit_remote_master" ]; then
    echo "Master on remote 'origin' has commits since the version under release, aborting"
    exit 1
  fi
}

#----------------------
# MAIN
#----------------------

if ! is_pull_request && build_started_by_tag; then
  check_travis_branch_equals_travis_tag
  check_release_tag
fi

# During a release upload, don't run tests as they can flake or overrun the max time allowed by Travis.
if is_release_version; then
  true
else
  # verify runs both tests and integration tests (Docker tests included)
  ./mvnw verify -nsu
fi

# If we are on a pull request, our only job is to run tests, which happened above via ./mvnw install
if is_pull_request; then
  true

# If we are on master, we will deploy the latest snapshot or release version
#  * If a release commit fails to deploy for a transient reason, drop to staging repository in
#    Sonatype and try again: https://oss.sonatype.org/#stagingRepositories
elif is_travis_branch_master; then
  # -Prelease ensures the core jar ends up JRE 1.6 compatible
  DEPLOY="./mvnw --batch-mode -s ./.settings.xml -Prelease -nsu -DskipTests deploy"

  # -DskipBenchmarks ensures benchmarks don't end up in javadocs or in Maven Central
  $DEPLOY -DskipBenchmarks -pl -:zipkin-reporter-bom
  # Deploy the Bill of Materials (BOM) separately as it is unhooked from the main project intentionally
  $DEPLOY -f bom/pom.xml

  if is_release_version; then
    # cleanup the release trigger, but don't fail if it was already there
    git push origin :"release-$(print_project_version)" || true
  fi

# If we are on a release tag, the following will update any version references and push a version tag for deployment.
elif build_started_by_tag; then
  safe_checkout_master
  ./mvnw --batch-mode -s ./.settings.xml -Prelease -nsu -DreleaseVersion="$(release_version)" -Darguments="-DskipTests" release:prepare
fi
