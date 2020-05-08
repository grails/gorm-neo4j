package grails.gorm.tests

import grails.gorm.annotation.Entity

import javax.validation.constraints.Digits

/**
 * Created by graemerocher on 30/12/2016.
 */
class JavaxValidationSpec extends GormDatastoreSpec {

    void "test javax.validator validation"() {
        when:"An invalid entity is created"
        TestProduct p = new TestProduct(name:"MacBook", price: "bad")
        p.save()

        then:"The are errors"
        p.hasErrors()
        p.errors.getFieldError('price')
    }

    @Override
    List getDomainClasses() {
        [TestProduct]
    }
}

@Entity
class TestProduct {
    @Digits(integer = 6, fraction = 2)
    String price
    String name
}
