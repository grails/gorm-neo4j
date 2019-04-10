#!/bin/bash

EXIT_STATUS=0

if [ "${TRAVIS_JDK_VERSION}" == "openjdk11" ] ; then
  exit $EXIT_STATUS
fi

echo "Publishing for branch $TRAVIS_BRANCH JDK: $TRAVIS_JDK_VERSION"

if [[ $TRAVIS_REPO_SLUG == "grails/gorm-neo4j" && $TRAVIS_PULL_REQUEST == 'false' && $EXIT_STATUS -eq 0 ]]; then

  echo "Publishing archives"
  export GRADLE_OPTS="-Xmx1500m -Dfile.encoding=UTF-8"
  if [[ $TRAVIS_TAG =~ ^v[[:digit:]] ]]; then
    # for releases we upload to Bintray and Sonatype OSS
      if [[ -n $TRAVIS_TAG ]]; then
          ./gradlew publish bintrayUpload --no-daemon --stacktrace || EXIT_STATUS=$?
      else
          ./gradlew publish --no-daemon --stacktrace || EXIT_STATUS=$?
      fi
  else
    echo "publishing snapshot"
    # for snapshots only to repo.grails.org
    ./gradlew publish || EXIT_STATUS=$?
  fi

  if [[ $EXIT_STATUS -eq 0 ]]; then
    echo "Publishing Successful."

    echo "Publishing Documentation..."
    ./gradlew docs:docs

    git config --global user.name "$GIT_NAME"
    git config --global user.email "$GIT_EMAIL"
    git config --global credential.helper "store --file=~/.git-credentials"
    echo "https://$GH_TOKEN:@github.com" > ~/.git-credentials


    git clone https://${GH_TOKEN}@github.com/grails/grails-data-mapping.git -b gh-pages gh-pages --single-branch > /dev/null
    cd gh-pages

    if [[ -n $TRAVIS_TAG ]]; then
        version="$TRAVIS_TAG"
        version=${version:1}

        if [[ $TRAVIS_TAG =~ [M\d|RC\d] ]]; then
           echo "Publishing Milestone Release"  
        else 
            mkdir -p latest/neo4j
            cp -r ../docs/build/docs/. ./latest/neo4j/
            git add latest/neo4j/*
        fi   

        majorVersion=${version:0:4}
        majorVersion="${majorVersion}x"

        mkdir -p "$version/neo4j"
        cp -r ../docs/build/docs/. "./$version/neo4j/"
        git add "$version/neo4j/*"

        mkdir -p "$majorVersion/neo4j"
        cp -r ../docs/build/docs/. "./$majorVersion/neo4j/"
        git add "$majorVersion/neo4j/*"

    else
        # If this is the master branch then update the snapshot
        mkdir -p snapshot/neo4j
        cp -r ../docs/build/docs/. ./snapshot/neo4j/

        git add snapshot/neo4j/*
    fi


    git commit -a -m "Updating Neo4j Docs for Travis build: https://travis-ci.org/$TRAVIS_REPO_SLUG/builds/$TRAVIS_BUILD_ID"
    git push origin HEAD
    cd ..
    rm -rf gh-pages

    if [[ $EXIT_STATUS -eq 0 ]]; then
        if [[ -n $TRAVIS_TAG ]]; then
          ./gradlew synchronizeWithMavenCentral --no-daemon
        fi
    fi    
  fi   
fi

exit $EXIT_STATUS
