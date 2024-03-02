package example

import grails.gorm.transactions.Transactional
import org.springframework.transaction.annotation.Propagation

@Transactional
class LibraryService {

    BookService bookService
    PersonService personService

    @Transactional(readOnly = true)
    Boolean bookExists(Serializable id) {
        assert bookService != null
        bookService.get(id)
    }

    @Transactional(timeout = 60, propagation = Propagation.REQUIRES_NEW)
    Book getBookWithLongTimeout(Serializable id) {
        Book.executeQuery('CALL grails.sleep(4000) MATCH (n:Book) WHERE ( ID(n)=$1 ) RETURN n as data', [id])
    }

    @Transactional(timeout = 1, propagation = Propagation.REQUIRES_NEW)
    Book getBookWithShortTimeout(Serializable id) {
        Book.executeQuery('CALL grails.sleep(4000) MATCH (n:Book) WHERE ( ID(n)=$1 ) RETURN n as data', [id])
    }

    Person addMember(String firstName, String lastName) {
        assert personService != null
        personService.save(firstName, lastName)
    }

}
