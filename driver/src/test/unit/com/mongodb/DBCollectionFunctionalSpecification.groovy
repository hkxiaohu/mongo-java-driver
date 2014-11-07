/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb

import spock.lang.IgnoreIf

import static com.mongodb.ClusterFixture.serverVersionAtLeast
import static java.util.Arrays.asList
import static org.hamcrest.Matchers.contains
import static spock.util.matcher.HamcrestSupport.that

@SuppressWarnings('DuplicateMapLiteral')
class DBCollectionFunctionalSpecification extends FunctionalSpecification {
    private idOfExistingDocument

    def setupSpec() {
        Map.metaClass.bitwiseNegate = { new BasicDBObject(delegate) }
    }

    def setup() {
        def existingDocument = ~['a': ~[:],
                                 'b': ~[:]]
        collection.insert(existingDocument);
        idOfExistingDocument = existingDocument.get('_id')
        collection.setObjectClass(BasicDBObject)
    }

    def 'should update a document'() {
        when:
        collection.update(new BasicDBObject('_id', 1), new BasicDBObject('$set', new BasicDBObject('x', 1)), true, false)

        then:
        collection.findOne(new BasicDBObject('_id', 1)) == new BasicDBObject('_id', 1).append('x', 1)

        when:
        collection.update(new BasicDBObject('_id', 2), new BasicDBObject('$set', new BasicDBObject('x', 1)));

        then:
        collection.findOne(new BasicDBObject('_id', 2)) == null
    }

    def 'should update multiple documents'() {
        given:
        collection.insert([new BasicDBObject('x', 1), new BasicDBObject('x', 1)])

        when:
        collection.update(new BasicDBObject('x', 1), new BasicDBObject('$set', new BasicDBObject('x', 2)), false, true);

        then:
        collection.count(new BasicDBObject('x', 2)) == 2
    }

    def 'should replace a document'() {
        when:
        collection.update(new BasicDBObject('_id', 1), new BasicDBObject('_id', 1).append('x', 1), true, false)

        then:
        collection.findOne(new BasicDBObject('_id', 1)) == new BasicDBObject('_id', 1).append('x', 1)

        when:
        collection.update(new BasicDBObject('_id', 2), new BasicDBObject('_id', 2).append('x', 1))

        then:
        collection.findOne(new BasicDBObject('_id', 2)) == null
    }

    def 'should drop collection that exists'() {
        given:
        collection.insert(~['name': 'myName']);

        when:
        collection.drop();

        then:
        !(this.collectionName in database.getCollectionNames())
    }

    def 'should not error when dropping a collection that does not exist'() {
        given:
        !(this.collectionName in database.getCollectionNames())

        when:
        collection.drop();

        then:
        notThrown(MongoException)
    }

    def 'should use top-level class for findAndModify'() {
        given:
        collection.setObjectClass(ClassA)

        when:
        DBObject document = collection.findAndModify(null, ~['_id': idOfExistingDocument, 'c': 1])

        then:
        document instanceof ClassA

    }

    def 'should use internal classes for findAndModify'() {
        given:
        collection.setInternalClass('a', ClassA);
        collection.setInternalClass('b', ClassB);

        when:
        DBObject document = collection.findAndModify(null, ~['_id': idOfExistingDocument, 'c': 1])

        then:
        document.get('a') instanceof ClassA
        document.get('b') instanceof ClassB
    }

    def 'should support index options'() {
        given:
        def options = ~[
                'sparse'            : true,
                'background'        : true,
                'expireAfterSeconds': 42
        ]

        when:
        collection.createIndex(~['y': 1], options);

        then:
        collection.getIndexInfo().size() == 2

        DBObject document = collection.getIndexInfo()[1]
        document.get('expireAfterSeconds') == 42
        document.get('background') == true
    }

    @IgnoreIf({ serverVersionAtLeast(asList(2, 7, 0)) })
    def 'should support legacy dropDups when creating a unique index'() {
        when:
        collection.drop()
        collection.insert(~['y': 1])
        collection.insert(~['y': 1])

        then:
        collection.count() == 2

        when:
        collection.createIndex(~['y': 1], ~['unique': true]);

        then:
        thrown(DuplicateKeyException)

        when:
        collection.createIndex(~['y': 1], ~['unique': true, 'dropDups': true]);

        then:
        notThrown(DuplicateKeyException)
        collection.count() == 1
    }

    def 'drop index should not fail if collection does not exist'() {
        given:
        collection.drop();

        expect:
        collection.dropIndex('indexOnCollectionThatDoesNotExist');
    }

    def 'drop index should error if index does not exist'() {
        given:
        collection.createIndex(new BasicDBObject('x', 1));

        when:
        collection.dropIndex('y_1');

        then:
        CommandFailureException exception = thrown(CommandFailureException)
        exception.getErrorMessage().contains('index not found')
    }

    def 'should throw Exception if dropping an index with an incorrect type'() {
        given:
        BasicDBObject index = new BasicDBObject('x', 1);
        collection.createIndex(index);

        when:
        collection.dropIndex(new BasicDBObject('x', '2d'));

        then:
        CommandFailureException exception = thrown(CommandFailureException)
        exception.getErrorMessage().contains('index not found')
    }

    def 'should drop nested index'() {
        given:
        collection.save(new BasicDBObject('x', new BasicDBObject('y', 1)));
        BasicDBObject index = new BasicDBObject('x.y', 1);
        collection.createIndex(index);
        assert collection.indexInfo.size() == 2

        when:
        collection.dropIndex(index);

        then:
        collection.indexInfo.size() == 1
    }

    def 'should drop all indexes except the default index on _id'() {
        given:
        collection.createIndex(new BasicDBObject('x', 1));
        collection.createIndex(new BasicDBObject('x.y', 1));
        assert collection.indexInfo.size() == 3

        when:
        collection.dropIndexes();

        then:
        collection.indexInfo.size() == 1
    }

    def 'should drop unique index'() {
        given:
        BasicDBObject index = new BasicDBObject('x', 1);
        collection.createIndex(index, new BasicDBObject('unique', true));

        when:
        collection.dropIndex(index);

        then:
        collection.indexInfo.size() == 1
    }

    def 'should use compound index for min query'() {
        given:
        collection.createIndex(new BasicDBObject('a', 1).append('_id', 1));

        when:
        def cursor = collection.find().min(new BasicDBObject('a', 1).append('_id', idOfExistingDocument))

        then:
        cursor.size() == 1
    }

    def 'should be able to rename a collection'() {
        given:
        assert database.getCollectionNames().contains(collectionName)
        String newCollectionName = 'someNewName'

        when:
        collection.rename(newCollectionName);

        then:
        !database.getCollectionNames().contains(collectionName)

        database.getCollection(newCollectionName) != null
        database.getCollectionNames().contains(newCollectionName)
    }

    def 'should be able to rename collection to an existing collection name and replace it when drop is true'() {
        given:
        String existingCollectionName = 'anExistingCollection';
        String originalCollectionName = 'someOriginalCollection';

        DBCollection originalCollection = database.getCollection(originalCollectionName);
        String keyInOriginalCollection = 'someKey';
        String valueInOriginalCollection = 'someValue';
        originalCollection.insert(new BasicDBObject(keyInOriginalCollection, valueInOriginalCollection));

        DBCollection existingCollection = database.getCollection(existingCollectionName);
        String keyInExistingCollection = 'aDifferentDocument';
        existingCollection.insert(new BasicDBObject(keyInExistingCollection, 'withADifferentValue'));

        assert database.getCollectionNames().contains(originalCollectionName)
        assert database.getCollectionNames().contains(existingCollectionName)

        when:
        originalCollection.rename(existingCollectionName, true);

        then:
        !database.getCollectionNames().contains(originalCollectionName)
        database.getCollectionNames().contains(existingCollectionName)

        DBCollection replacedCollection = database.getCollection(existingCollectionName);
        replacedCollection.findOne().get(keyInExistingCollection) == null
        replacedCollection.findOne().get(keyInOriginalCollection).toString() == valueInOriginalCollection
    }

    def 'should return a list of all the values of a given field without duplicates'() {
        given:
        collection.drop()
        for (
                int i = 0;
                i < 100;
                i++) {
            def document = ~['_id': i,
                             'x'  : i % 10]
            collection.save(document);
        }
        assert collection.count() == 100;

        when:
        List distinctValuesOfFieldX = collection.distinct('x')

        then:
        distinctValuesOfFieldX.size() == 10
        that distinctValuesOfFieldX, contains(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    }

    def 'should query database for values and return a list of all the distinct values of a given field that match the filter'() {
        given:
        collection.drop()
        for (
                int i = 0;
                i < 100;
                i++) {
            def document = ~['_id'        : i,
                             'x'          : i % 10,
                             'isOddNumber': i % 2]
            collection.save(document);
        }
        assert collection.count() == 100;

        when:
        List distinctValuesOfFieldX = collection.distinct('x', ~['isOddNumber': 1]);

        then:
        distinctValuesOfFieldX.size() == 5
        that distinctValuesOfFieldX, contains(1, 3, 5, 7, 9)
    }

    @SuppressWarnings('UnnecessaryObjectReferences')
    def insertDataForGroupTests() {
        collection.drop();
        collection.save(~['x': 'a']);
        collection.save(~['x': 'a']);
        collection.save(~['x': 'a']);
        collection.save(~['x': 'b']);
        collection.save(~['x': 'b']);
        collection.save(~['x': 'b']);
        collection.save(~['x': 'b']);
        collection.save(~['x': 'c']);
    }

    def 'should group by the x field and apply the provided count function'() {
        given:
        insertDataForGroupTests()

        when:
        DBObject result = collection.group(~['x': 1],
                                           null,
                                           ~['count': 0],
                                           'function(o , p){ p.count++; }');

        then:
        result.size() == 3
        that result, contains(~['x': 'a', 'count': 3.0D], ~['x': 'b', 'count': 4.0D], ~['x': 'c', 'count': 1.0D]);
    }

    def 'should group by the x field and apply the provided count function using a GroupCommand'() {
        given:
        insertDataForGroupTests()
        GroupCommand command = new GroupCommand(collection,
                                                ~['x': 1],
                                                null,
                                                ~['count': 0],
                                                'function(o , p){ p.count++; }',
                                                null);

        when:
        DBObject result = collection.group(command);

        then:
        result.size() == 3
        that result, contains(~['x': 'a', 'count': 3.0D], ~['x': 'b', 'count': 4.0D], ~['x': 'c', 'count': 1.0D]);
    }

    def 'should group and count only those documents that fulfull the given condition'() {
        given:
        insertDataForGroupTests()

        when:
        def result = collection.group(~['x': 1],
                                      ~['x': 'b'],
                                      ~['count': 0],
                                      'function(o , p){ p.count++; }')

        then:
        result.size() == 1;
        that result, contains(~['x': 'b', 'count': 4.0D]);
    }

    def 'should be able to set a value in the results'() {
        given:
        insertDataForGroupTests()

        when:
        def result = collection.group(~['x': 1],
                                      ~['x': 'c'],
                                      ~['valueFound': false],
                                      'function(o, p){ p.valueFound = true; }');

        then:
        result.size() == 1;
        that result, contains(~['x': 'c', 'valueFound': true]);
    }

    def 'should return results set by the function, based on condition supplied without key'() {
        given:
        insertDataForGroupTests()

        when:
        def result = collection.group(null,
                                      ~['x': 'b'],
                                      ~['valueFound': 0],
                                      'function(o, p){ p.valueFound = true; }');

        then:
        result.size() == 1
        that result, contains(~['valueFound': true]);
    }

    def 'finalize function should replace the whole result document if a document is the return type of finalize function'() {
        given:
        insertDataForGroupTests()

        when:
        def result = collection.group(~['x': 1],
                                      ~['x': 'b'],
                                      ~['valueFound': 0],
                                      'function(o, p){ p.valueFound = true; }',
                                      'function(o){ return { a:1 } }');

        then:
        result.size() == 1;
        that result, contains(~['a': 1.0D]);
    }

    def 'finalize function should modify the result document'() {
        given:
        insertDataForGroupTests()

        when:
        def result = collection.group(~['x': 1],
                                      ~['x': 'b'],
                                      ~['valueFound': 0],
                                      'function(o, p){ p.valueFound = true; }',
                                      'function(o){ o.a = 1 }');

        then:
        result.size() == 1;
        that result, contains(~['x': 'b', 'valueFound': true, 'a': 1.0D]);
    }

    def 'should throw an Exception if no initial document provided'() {
        when:
        collection.group(~['x': 1],
                         ~['x': 'b'],
                         null,
                         'function(o, p){ p.valueFound = true; }');

        then:
        thrown(IllegalArgumentException)
    }

    def 'should throw an Exception if no reduce function provided'() {
        when:
        collection.group(~['x': 1],
                         ~['x': 'b'],
                         ~['z': 0],
                         null);

        then:
        thrown(IllegalArgumentException)
    }

    static class ClassA extends BasicDBObject { }

    static class ClassB extends BasicDBObject { }
}