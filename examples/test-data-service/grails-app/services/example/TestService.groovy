package example

class TestService {

    LibraryService libraryService

    Boolean testDataService(Serializable id)  {
        libraryService.bookExists(id)
    }

    Book testDataServiceWithLongTimeout(Serializable id)  {
        libraryService.getBookWithLongTimeout(id)
    }

    Book testDataServiceWithShortTimeout(Serializable id)  {
        libraryService.getBookWithShortTimeout(id)
    }

    Person save(String firstName, String lastName) {
        libraryService.addMember(firstName, lastName)
    }
}
