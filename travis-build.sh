#!/bin/bash
EXIT_STATUS=0

./gradlew --stop

./gradlew compileTestGroovy -x grails2-plugin:compileTestGroovy

if [[ -n $TRAVIS_TAG ]]; then
    echo "Skipping tests to publish release"

else
    ./gradlew check --refresh-dependencies -no-daemon -x grails2-plugin:test -x gorm-neo4j-spring-boot:test  || EXIT_STATUS=$?
    if [[ $EXIT_STATUS -eq 0 ]]; then
        ./gradlew grails2-plugin:test --refresh-dependencies -no-daemon || EXIT_STATUS=$?
    fi
    if [[ $EXIT_STATUS -eq 0 ]]; then
        ./gradlew gorm-neo4j-spring-boot:test --refresh-dependencies -no-daemon || EXIT_STATUS=$?
    fi
fi

./gradlew --stop

if [[ $EXIT_STATUS -eq 0 ]]; then
    ./travis-publish.sh || EXIT_STATUS=$?
fi

exit $EXIT_STATUS



