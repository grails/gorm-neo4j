#!/bin/bash
EXIT_STATUS=0

./gradlew compileTestGroovy

if [[ -n $TRAVIS_TAG ]]; then
    echo "Skipping tests to publish release"

else
    ./gradlew -Dgeb.env=chromeHeadless check --refresh-dependencies -no-daemon -x gorm-neo4j-spring-boot:test  || EXIT_STATUS=$?
    if [[ $EXIT_STATUS -eq 0 ]]; then
        ./gradlew gorm-neo4j-spring-boot:test --refresh-dependencies -no-daemon || EXIT_STATUS=$?
    fi
fi

if [[ $EXIT_STATUS -eq 0 ]]; then
    ./travis-publish.sh || EXIT_STATUS=$?
fi

exit $EXIT_STATUS



