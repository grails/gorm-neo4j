package grails.gorm.tests

import spock.lang.Issue

/*
 * Copyright 2014 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @author graemerocher
 */
class OneToManyUpdateSpec extends GormDatastoreSpec {


    @Issue('https://github.com/grails/grails-data-mapping/issues/575')
    void "Test updates to one to many don't create duplicate relationships"() {
        given:" a one to many relationship"
        Club club = new Club(name:"Manchester United")
        club.addToTeams(name:"First Team")
        club.save(flush:true)

        Tournament t = new Tournament(name: "FA Cup")
        t.addToTeams(club.teams.first())
        t.save(flush:true)
        session.clear()

        when:"it is first read"

        t = Tournament.get(t.id)
        session.clear()
        Team team = Team.first()

        then: "the relationship is correct"
        t != null
        t.teams.size() == 1
        team != null

        when:"An existing instance is added"

        t.addToTeams(name:"Second Team")
        t.addToTeams(team)
        t.save(flush:true)

        session.clear()

        t = Tournament.get(t.id)

        def result = Club.cypherStatic('MATCH (from:Tournament)-[r:TEAMS]->(to:Team) WHERE from.__id__ = {id}  RETURN r', [id:t.id])
        then: "the relationship is correct and no duplicates are added"
        t != null
        t.teams.size() == 2
        result.iterator().size() == 2


        when:"A relationship is removed"
        def secondTeam = t.teams.find { it.name == "Second Team"}
        t.removeFromTeams(secondTeam)
        t.save(flush:true)
        session.clear()

        t = Tournament.get(t.id)
        result = Club.cypherStatic('MATCH (from:Tournament)-[r:TEAMS]->(to:Team) WHERE from.__id__ = {id}  RETURN r', [id:t.id])
        then: "the relationship is correct and no duplicates are added"
        t != null
        t.teams.size() == 1
        result.iterator().size() == 1


    }

    @Issue('https://github.com/grails/gorm-neo4j/issues/26')
    void "test that setting an association to null clears the relationship"() {
        given:
        Club c = new Club(name: "Manchester United").save(validate:false)
        Team t = new Team(name: "First Team", club: c).save(flush:true, validate:false)
        session.clear()

        when:"A instance is retrieved"
        t = Team.first()

        then:"it isn't null"
        t != null
        t.club != null

        when:"The association is set to null"
        t.club = null
        assert t.hasChanged('club')
        t.save(flush:true, validate:false)
        session.clear()

        t = Team.first()
        then:"The association was cleared"
        t != null
        t.club == null

    }

    void "test dirty checkable"() {
        when:
        def gcl = new GroovyClassLoader()
        def cls = gcl.parseClass('''

@grails.gorm.annotation.Entity
class Team1 {
    Club club
}

@grails.gorm.annotation.Entity
class Club {

}

def t = new Team1()
t.trackChanges()
t.club = null
return t.hasChanged("club")
''').newInstance().run()
        then:
        cls != null
    }
    @Override
    List getDomainClasses() {
        [Tournament, Club, Team]
    }
}
