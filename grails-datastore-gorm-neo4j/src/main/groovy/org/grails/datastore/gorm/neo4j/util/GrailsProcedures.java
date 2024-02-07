package org.grails.datastore.gorm.neo4j.util;

import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

/**
 * Grails procedures for Neo4j embedded server.
 */
public class GrailsProcedures {

  /**
   * Procedure for Neo4j that sleeps for the specified number of milliseconds.
   * @param millis the length of time to sleep in milliseconds
   */
  @Procedure("grails.sleep")
  @Description("Sleep for the specified number of milliseconds.")
  public void sleep(@Name(value = "millis", defaultValue = "1000") Long millis) {
    try {
      Thread.sleep(millis);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
