/*
 * Copyright 2011-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.core.convert;

import static java.time.ZoneId.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.data.mongodb.core.DocumentTestUtils.*;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;

import org.bson.types.Code;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.joda.time.LocalDate;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.geo.Box;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.geo.Polygon;
import org.springframework.data.geo.Shape;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.model.MappingInstantiationException;
import org.springframework.data.mongodb.core.DocumentTestUtils;
import org.springframework.data.mongodb.core.convert.DocumentAccessorUnitTests.NestedType;
import org.springframework.data.mongodb.core.convert.DocumentAccessorUnitTests.ProjectingType;
import org.springframework.data.mongodb.core.convert.MappingMongoConverterUnitTests.ClassWithMapUsingEnumAsKey.FooBarEnum;
import org.springframework.data.mongodb.core.geo.Sphere;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.mapping.PersonPojoStringId;
import org.springframework.data.mongodb.core.mapping.TextScore;
import org.springframework.data.util.ClassTypeInformation;
import org.springframework.test.util.ReflectionTestUtils;

import com.mongodb.BasicDBList;
import com.mongodb.DBRef;

/**
 * Unit tests for {@link MappingMongoConverter}.
 *
 * @author Oliver Gierke
 * @author Patrik Wasik
 * @author Christoph Strobl
 * @author Mark Paluch
 */
@RunWith(MockitoJUnitRunner.class)
public class MappingMongoConverterUnitTests {

	MappingMongoConverter converter;
	MongoMappingContext mappingContext;
	@Mock ApplicationContext context;
	@Mock DbRefResolver resolver;

	@Before
	public void setUp() {

		MongoCustomConversions conversions = new MongoCustomConversions();

		mappingContext = new MongoMappingContext();
		mappingContext.setApplicationContext(context);
		mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
		mappingContext.afterPropertiesSet();

		mappingContext.getPersistentEntity(Address.class);

		converter = new MappingMongoConverter(resolver, mappingContext);
		converter.setCustomConversions(conversions);
		converter.afterPropertiesSet();
	}

	@Test
	public void convertsAddressCorrectly() {

		Address address = new Address();
		address.city = "New York";
		address.street = "Broadway";

		org.bson.Document document = new org.bson.Document();

		converter.write(address, document);

		assertThat(document.get("city").toString()).isEqualTo("New York");
		assertThat(document.get("street").toString()).isEqualTo("Broadway");
	}

	@Test
	public void convertsJodaTimeTypesCorrectly() {

		converter = new MappingMongoConverter(resolver, mappingContext);
		converter.afterPropertiesSet();

		Person person = new Person();
		person.birthDate = new LocalDate();

		org.bson.Document document = new org.bson.Document();
		converter.write(person, document);

		assertThat(document.get("birthDate")).isInstanceOf(Date.class);

		Person result = converter.read(Person.class, document);
		assertThat(result.birthDate).isNotNull();
	}

	@Test
	public void convertsCustomTypeOnConvertToMongoType() {

		converter = new MappingMongoConverter(resolver, mappingContext);
		converter.afterPropertiesSet();

		LocalDate date = new LocalDate();
		converter.convertToMongoType(date);
	}

	@Test // DATAMONGO-130
	public void writesMapTypeCorrectly() {

		Map<Locale, String> map = Collections.singletonMap(Locale.US, "Foo");

		org.bson.Document document = new org.bson.Document();
		converter.write(map, document);

		assertThat(document.get(Locale.US.toString()).toString()).isEqualTo("Foo");
	}

	@Test // DATAMONGO-130
	public void readsMapWithCustomKeyTypeCorrectly() {

		org.bson.Document mapObject = new org.bson.Document(Locale.US.toString(), "Value");
		org.bson.Document document = new org.bson.Document("map", mapObject);

		ClassWithMapProperty result = converter.read(ClassWithMapProperty.class, document);
		assertThat(result.map.get(Locale.US)).isEqualTo("Value");
	}

	@Test // DATAMONGO-128
	public void usesDocumentsStoredTypeIfSubtypeOfRequest() {

		org.bson.Document document = new org.bson.Document();
		document.put("birthDate", new LocalDate());
		document.put(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY, Person.class.getName());

		assertThat(converter.read(Contact.class, document)).isInstanceOf(Person.class);
	}

	@Test // DATAMONGO-128
	public void ignoresDocumentsStoredTypeIfCompletelyDifferentTypeRequested() {

		org.bson.Document document = new org.bson.Document();
		document.put("birthDate", new LocalDate());
		document.put(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY, Person.class.getName());

		assertThat(converter.read(BirthDateContainer.class, document)).isInstanceOf(BirthDateContainer.class);
	}

	@Test
	public void writesTypeDiscriminatorIntoRootObject() {

		Person person = new Person();

		org.bson.Document result = new org.bson.Document();
		converter.write(person, result);

		assertThat(result.containsKey(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY)).isTrue();
		assertThat(result.get(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY).toString()).isEqualTo(Person.class.getName());
	}

	@Test // DATAMONGO-136
	public void writesEnumsCorrectly() {

		ClassWithEnumProperty value = new ClassWithEnumProperty();
		value.sampleEnum = SampleEnum.FIRST;

		org.bson.Document result = new org.bson.Document();
		converter.write(value, result);

		assertThat(result.get("sampleEnum")).isInstanceOf(String.class);
		assertThat(result.get("sampleEnum").toString()).isEqualTo("FIRST");
	}

	@Test // DATAMONGO-209
	public void writesEnumCollectionCorrectly() {

		ClassWithEnumProperty value = new ClassWithEnumProperty();
		value.enums = Arrays.asList(SampleEnum.FIRST);

		org.bson.Document result = new org.bson.Document();
		converter.write(value, result);

		assertThat(result.get("enums")).isInstanceOf(List.class);

		List<Object> enums = (List<Object>) result.get("enums");
		assertThat(enums.size()).isEqualTo(1);
		assertThat(enums.get(0)).isEqualTo("FIRST");
	}

	@Test // DATAMONGO-136
	public void readsEnumsCorrectly() {
		org.bson.Document document = new org.bson.Document("sampleEnum", "FIRST");
		ClassWithEnumProperty result = converter.read(ClassWithEnumProperty.class, document);

		assertThat(result.sampleEnum).isEqualTo(SampleEnum.FIRST);
	}

	@Test // DATAMONGO-209
	public void readsEnumCollectionsCorrectly() {

		BasicDBList enums = new BasicDBList();
		enums.add("FIRST");
		org.bson.Document document = new org.bson.Document("enums", enums);

		ClassWithEnumProperty result = converter.read(ClassWithEnumProperty.class, document);

		assertThat(result.enums).isInstanceOf(List.class);
		assertThat(result.enums.size()).isEqualTo(1);
		assertThat(result.enums).contains(SampleEnum.FIRST);
	}

	@Test // DATAMONGO-144
	public void considersFieldNameWhenWriting() {

		Person person = new Person();
		person.firstname = "Oliver";

		org.bson.Document result = new org.bson.Document();
		converter.write(person, result);

		assertThat(result.containsKey("foo")).isTrue();
		assertThat(result.containsKey("firstname")).isFalse();
	}

	@Test // DATAMONGO-144
	public void considersFieldNameWhenReading() {

		org.bson.Document document = new org.bson.Document("foo", "Oliver");
		Person result = converter.read(Person.class, document);

		assertThat(result.firstname).isEqualTo("Oliver");
	}

	@Test
	public void resolvesNestedComplexTypeForConstructorCorrectly() {

		org.bson.Document address = new org.bson.Document("street", "110 Southwark Street");
		address.put("city", "London");

		BasicDBList addresses = new BasicDBList();
		addresses.add(address);

		org.bson.Document person = new org.bson.Document("firstname", "Oliver");
		person.put("addresses", addresses);

		Person result = converter.read(Person.class, person);
		assertThat(result.addresses).isNotNull();
	}

	@Test // DATAMONGO-145
	public void writesCollectionWithInterfaceCorrectly() {

		Person person = new Person();
		person.firstname = "Oliver";

		CollectionWrapper wrapper = new CollectionWrapper();
		wrapper.contacts = Arrays.asList((Contact) person);

		org.bson.Document document = new org.bson.Document();
		converter.write(wrapper, document);

		Object result = document.get("contacts");
		assertThat(result).isInstanceOf(List.class);
		List<Object> contacts = (List<Object>) result;
		org.bson.Document personDocument = (org.bson.Document) contacts.get(0);
		assertThat(personDocument.get("foo").toString()).isEqualTo("Oliver");
		assertThat((String) personDocument.get(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY)).isEqualTo(Person.class.getName());
	}

	@Test // DATAMONGO-145
	public void readsCollectionWithInterfaceCorrectly() {

		org.bson.Document person = new org.bson.Document(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY, Person.class.getName());
		person.put("foo", "Oliver");

		BasicDBList contacts = new BasicDBList();
		contacts.add(person);

		CollectionWrapper result = converter.read(CollectionWrapper.class, new org.bson.Document("contacts", contacts));
		assertThat(result.contacts).isNotNull();
		assertThat(result.contacts.size()).isEqualTo(1);
		Contact contact = result.contacts.get(0);
		assertThat(contact).isInstanceOf(Person.class);
		assertThat(((Person) contact).firstname).isEqualTo("Oliver");
	}

	@Test
	public void convertsLocalesOutOfTheBox() {
		LocaleWrapper wrapper = new LocaleWrapper();
		wrapper.locale = Locale.US;

		org.bson.Document document = new org.bson.Document();
		converter.write(wrapper, document);

		Object localeField = document.get("locale");
		assertThat(localeField).isInstanceOf(String.class);
		assertThat(localeField).isEqualTo("en_US");

		LocaleWrapper read = converter.read(LocaleWrapper.class, document);
		assertThat(read.locale).isEqualTo(Locale.US);
	}

	@Test // DATAMONGO-161
	public void readsNestedMapsCorrectly() {

		Map<String, String> secondLevel = new HashMap<String, String>();
		secondLevel.put("key1", "value1");
		secondLevel.put("key2", "value2");

		Map<String, Map<String, String>> firstLevel = new HashMap<String, Map<String, String>>();
		firstLevel.put("level1", secondLevel);
		firstLevel.put("level2", secondLevel);

		ClassWithNestedMaps maps = new ClassWithNestedMaps();
		maps.nestedMaps = new LinkedHashMap<String, Map<String, Map<String, String>>>();
		maps.nestedMaps.put("afield", firstLevel);

		org.bson.Document document = new org.bson.Document();
		converter.write(maps, document);

		ClassWithNestedMaps result = converter.read(ClassWithNestedMaps.class, document);
		Map<String, Map<String, Map<String, String>>> nestedMap = result.nestedMaps;
		assertThat(nestedMap).isNotNull();
		assertThat(nestedMap.get("afield")).isEqualTo(firstLevel);
	}

	@Test // DATACMNS-42, DATAMONGO-171
	public void writesClassWithBigDecimal() {

		BigDecimalContainer container = new BigDecimalContainer();
		container.value = BigDecimal.valueOf(2.5d);
		container.map = Collections.singletonMap("foo", container.value);

		org.bson.Document document = new org.bson.Document();
		converter.write(container, document);

		assertThat(document.get("value")).isInstanceOf(String.class);
		assertThat((String) document.get("value")).isEqualTo("2.5");
		assertThat(((org.bson.Document) document.get("map")).get("foo")).isInstanceOf(String.class);
	}

	@Test // DATACMNS-42, DATAMONGO-171
	public void readsClassWithBigDecimal() {

		org.bson.Document document = new org.bson.Document("value", "2.5");
		document.put("map", new org.bson.Document("foo", "2.5"));

		BasicDBList list = new BasicDBList();
		list.add("2.5");
		document.put("collection", list);
		BigDecimalContainer result = converter.read(BigDecimalContainer.class, document);

		assertThat(result.value).isEqualTo(BigDecimal.valueOf(2.5d));
		assertThat(result.map.get("foo")).isEqualTo(BigDecimal.valueOf(2.5d));
		assertThat(result.collection.get(0)).isEqualTo(BigDecimal.valueOf(2.5d));
	}

	@Test
	public void writesNestedCollectionsCorrectly() {

		CollectionWrapper wrapper = new CollectionWrapper();
		wrapper.strings = Arrays.asList(Arrays.asList("Foo"));

		org.bson.Document document = new org.bson.Document();
		converter.write(wrapper, document);

		Object outerStrings = document.get("strings");
		assertThat(outerStrings).isInstanceOf(List.class);

		List<Object> typedOuterString = (List<Object>) outerStrings;
		assertThat(typedOuterString.size()).isEqualTo(1);
	}

	@Test // DATAMONGO-192
	public void readsEmptySetsCorrectly() {

		Person person = new Person();
		person.addresses = Collections.emptySet();

		org.bson.Document document = new org.bson.Document();
		converter.write(person, document);
		converter.read(Person.class, document);
	}

	@Test
	public void convertsObjectIdStringsToObjectIdCorrectly() {
		PersonPojoStringId p1 = new PersonPojoStringId("1234567890", "Text-1");
		org.bson.Document doc1 = new org.bson.Document();

		converter.write(p1, doc1);
		assertThat(doc1.get("_id")).isInstanceOf(String.class);

		PersonPojoStringId p2 = new PersonPojoStringId(new ObjectId().toString(), "Text-1");
		org.bson.Document doc2 = new org.bson.Document();

		converter.write(p2, doc2);
		assertThat(doc2.get("_id")).isInstanceOf(ObjectId.class);
	}

	@Test // DATAMONGO-207
	public void convertsCustomEmptyMapCorrectly() {

		org.bson.Document map = new org.bson.Document();
		org.bson.Document wrapper = new org.bson.Document("map", map);

		ClassWithSortedMap result = converter.read(ClassWithSortedMap.class, wrapper);

		assertThat(result).isInstanceOf(ClassWithSortedMap.class);
		assertThat(result.map).isInstanceOf(SortedMap.class);
	}

	@Test // DATAMONGO-211
	public void maybeConvertHandlesNullValuesCorrectly() {
		assertThat(converter.convertToMongoType(null)).isNull();
	}

	@Test // DATAMONGO-1509
	public void writesGenericTypeCorrectly() {

		GenericType<Address> type = new GenericType<Address>();
		type.content = new Address();
		type.content.city = "London";

		org.bson.Document result = new org.bson.Document();
		converter.write(type, result);

		org.bson.Document content = (org.bson.Document) result.get("content");
		assertTypeHint(content, Address.class);
		assertThat(content.get("city")).isNotNull();
	}

	@Test
	public void readsGenericTypeCorrectly() {

		org.bson.Document address = new org.bson.Document("_class", Address.class.getName());
		address.put("city", "London");

		GenericType<?> result = converter.read(GenericType.class, new org.bson.Document("content", address));
		assertThat(result.content).isInstanceOf(Address.class);
	}

	@Test // DATAMONGO-228
	public void writesNullValuesForMaps() {

		ClassWithMapProperty foo = new ClassWithMapProperty();
		foo.map = Collections.singletonMap(Locale.US, null);

		org.bson.Document result = new org.bson.Document();
		converter.write(foo, result);

		Object map = result.get("map");
		assertThat(map).isInstanceOf(org.bson.Document.class);
		assertThat(((org.bson.Document) map).keySet()).contains("en_US");
	}

	@Test
	public void writesBigIntegerIdCorrectly() {

		ClassWithBigIntegerId foo = new ClassWithBigIntegerId();
		foo.id = BigInteger.valueOf(23L);

		org.bson.Document result = new org.bson.Document();
		converter.write(foo, result);

		assertThat(result.get("_id")).isInstanceOf(String.class);
	}

	public void convertsObjectsIfNecessary() {

		ObjectId id = new ObjectId();
		assertThat(converter.convertToMongoType(id)).isEqualTo(id);
	}

	@Test // DATAMONGO-235
	public void writesMapOfListsCorrectly() {

		ClassWithMapProperty input = new ClassWithMapProperty();
		input.mapOfLists = Collections.singletonMap("Foo", Arrays.asList("Bar"));

		org.bson.Document result = new org.bson.Document();
		converter.write(input, result);

		Object field = result.get("mapOfLists");
		assertThat(field).isInstanceOf(org.bson.Document.class);

		org.bson.Document map = (org.bson.Document) field;
		Object foo = map.get("Foo");
		assertThat(foo).isInstanceOf(List.class);

		List<Object> value = (List<Object>) foo;
		assertThat(value.size()).isEqualTo(1);
		assertThat(value.get(0)).isEqualTo("Bar");
	}

	@Test // DATAMONGO-235
	public void readsMapListValuesCorrectly() {

		BasicDBList list = new BasicDBList();
		list.add("Bar");
		org.bson.Document source = new org.bson.Document("mapOfLists", new org.bson.Document("Foo", list));

		ClassWithMapProperty result = converter.read(ClassWithMapProperty.class, source);
		assertThat(result.mapOfLists).isNotNull();
	}

	@Test // DATAMONGO-235
	public void writesMapsOfObjectsCorrectly() {

		ClassWithMapProperty input = new ClassWithMapProperty();
		input.mapOfObjects = new HashMap<String, Object>();
		input.mapOfObjects.put("Foo", Arrays.asList("Bar"));

		org.bson.Document result = new org.bson.Document();
		converter.write(input, result);

		Object field = result.get("mapOfObjects");
		assertThat(field).isInstanceOf(org.bson.Document.class);

		org.bson.Document map = (org.bson.Document) field;
		Object foo = map.get("Foo");
		assertThat(foo).isInstanceOf(BasicDBList.class);

		BasicDBList value = (BasicDBList) foo;
		assertThat(value.size()).isEqualTo(1);
		assertThat(value.get(0)).isEqualTo("Bar");
	}

	@Test // DATAMONGO-235
	public void readsMapOfObjectsListValuesCorrectly() {

		BasicDBList list = new BasicDBList();
		list.add("Bar");
		org.bson.Document source = new org.bson.Document("mapOfObjects", new org.bson.Document("Foo", list));

		ClassWithMapProperty result = converter.read(ClassWithMapProperty.class, source);
		assertThat(result.mapOfObjects).isNotNull();
	}

	@Test // DATAMONGO-245
	public void readsMapListNestedValuesCorrectly() {

		BasicDBList list = new BasicDBList();
		list.add(new org.bson.Document("Hello", "World"));
		org.bson.Document source = new org.bson.Document("mapOfObjects", new org.bson.Document("Foo", list));

		ClassWithMapProperty result = converter.read(ClassWithMapProperty.class, source);
		Object firstObjectInFoo = ((List<?>) result.mapOfObjects.get("Foo")).get(0);
		assertThat(firstObjectInFoo).isInstanceOf(Map.class);
		assertThat(((Map<?, ?>) firstObjectInFoo).get("Hello")).isEqualTo("World");
	}

	@Test // DATAMONGO-245
	public void readsMapDoublyNestedValuesCorrectly() {

		org.bson.Document nested = new org.bson.Document();
		org.bson.Document doubly = new org.bson.Document();
		doubly.append("Hello", "World");
		nested.append("nested", doubly);
		org.bson.Document source = new org.bson.Document("mapOfObjects", new org.bson.Document("Foo", nested));

		ClassWithMapProperty result = converter.read(ClassWithMapProperty.class, source);
		Object foo = result.mapOfObjects.get("Foo");
		assertThat(foo).isInstanceOf(Map.class);
		Object doublyNestedObject = ((Map<?, ?>) foo).get("nested");
		assertThat(doublyNestedObject).isInstanceOf(Map.class);
		assertThat(((Map<?, ?>) doublyNestedObject).get("Hello")).isEqualTo("World");
	}

	@Test // DATAMONGO-245
	public void readsMapListDoublyNestedValuesCorrectly() {

		BasicDBList list = new BasicDBList();
		org.bson.Document nested = new org.bson.Document();
		org.bson.Document doubly = new org.bson.Document();
		doubly.append("Hello", "World");
		nested.append("nested", doubly);
		list.add(nested);
		org.bson.Document source = new org.bson.Document("mapOfObjects", new org.bson.Document("Foo", list));

		ClassWithMapProperty result = converter.read(ClassWithMapProperty.class, source);
		Object firstObjectInFoo = ((List<?>) result.mapOfObjects.get("Foo")).get(0);
		assertThat(firstObjectInFoo).isInstanceOf(Map.class);
		Object doublyNestedObject = ((Map<?, ?>) firstObjectInFoo).get("nested");
		assertThat(doublyNestedObject).isInstanceOf(Map.class);
		assertThat(((Map<?, ?>) doublyNestedObject).get("Hello")).isEqualTo("World");
	}

	@Test // DATAMONGO-259
	public void writesListOfMapsCorrectly() {

		Map<String, Locale> map = Collections.singletonMap("Foo", Locale.ENGLISH);

		CollectionWrapper wrapper = new CollectionWrapper();
		wrapper.listOfMaps = new ArrayList<Map<String, Locale>>();
		wrapper.listOfMaps.add(map);

		org.bson.Document result = new org.bson.Document();
		converter.write(wrapper, result);

		List<Object> list = (List<Object>) result.get("listOfMaps");
		assertThat(list).isNotNull();
		assertThat(list.size()).isEqualTo(1);

		org.bson.Document document = (org.bson.Document) list.get(0);
		assertThat(document.containsKey("Foo")).isTrue();
		assertThat((String) document.get("Foo")).isEqualTo(Locale.ENGLISH.toString());
	}

	@Test // DATAMONGO-259
	public void readsListOfMapsCorrectly() {

		org.bson.Document map = new org.bson.Document("Foo", "en");

		BasicDBList list = new BasicDBList();
		list.add(map);

		org.bson.Document wrapperSource = new org.bson.Document("listOfMaps", list);

		CollectionWrapper wrapper = converter.read(CollectionWrapper.class, wrapperSource);

		assertThat(wrapper.listOfMaps).isNotNull();
		assertThat(wrapper.listOfMaps.size()).isEqualTo(1);
		assertThat(wrapper.listOfMaps.get(0)).isNotNull();
		assertThat(wrapper.listOfMaps.get(0).get("Foo")).isEqualTo(Locale.ENGLISH);
	}

	@Test // DATAMONGO-259
	public void writesPlainMapOfCollectionsCorrectly() {

		Map<String, List<Locale>> map = Collections.singletonMap("Foo", Arrays.asList(Locale.US));
		org.bson.Document result = new org.bson.Document();
		converter.write(map, result);

		assertThat(result.containsKey("Foo")).isTrue();
		assertThat(result.get("Foo")).isNotNull();
		assertThat(result.get("Foo")).isInstanceOf(BasicDBList.class);

		BasicDBList list = (BasicDBList) result.get("Foo");

		assertThat(list.size()).isEqualTo(1);
		assertThat(list.get(0)).isEqualTo(Locale.US.toString());
	}

	@Test // DATAMONGO-285
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testSaveMapWithACollectionAsValue() {

		Map<String, Object> keyValues = new HashMap<String, Object>();
		keyValues.put("string", "hello");
		List<String> list = new ArrayList<String>();
		list.add("ping");
		list.add("pong");
		keyValues.put("list", list);

		org.bson.Document document = new org.bson.Document();
		converter.write(keyValues, document);

		Map<String, Object> keyValuesFromMongo = converter.read(Map.class, document);

		assertThat(keyValuesFromMongo.size()).isEqualTo(keyValues.size());
		assertThat(keyValuesFromMongo.get("string")).isEqualTo(keyValues.get("string"));
		assertThat(List.class.isAssignableFrom(keyValuesFromMongo.get("list").getClass())).isTrue();
		List<String> listFromMongo = (List) keyValuesFromMongo.get("list");
		assertThat(listFromMongo.size()).isEqualTo(list.size());
		assertThat(listFromMongo.get(0)).isEqualTo(list.get(0));
		assertThat(listFromMongo.get(1)).isEqualTo(list.get(1));
	}

	@Test // DATAMONGO-309
	@SuppressWarnings({ "unchecked" })
	public void writesArraysAsMapValuesCorrectly() {

		ClassWithMapProperty wrapper = new ClassWithMapProperty();
		wrapper.mapOfObjects = new HashMap<String, Object>();
		wrapper.mapOfObjects.put("foo", new String[] { "bar" });

		org.bson.Document result = new org.bson.Document();
		converter.write(wrapper, result);

		Object mapObject = result.get("mapOfObjects");
		assertThat(mapObject).isInstanceOf(org.bson.Document.class);

		org.bson.Document map = (org.bson.Document) mapObject;
		Object valueObject = map.get("foo");
		assertThat(valueObject).isInstanceOf(BasicDBList.class);

		List<Object> list = (List<Object>) valueObject;
		assertThat(list.size()).isEqualTo(1);
		assertThat(list).contains((Object) "bar");
	}

	@Test // DATAMONGO-324
	public void writesDocumentCorrectly() {

		org.bson.Document document = new org.bson.Document();
		document.put("foo", "bar");

		org.bson.Document result = new org.bson.Document();

		converter.write(document, result);

		result.remove(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY);
		assertThat(document).isEqualTo(result);
	}

	@Test // DATAMONGO-324
	public void readsDocumentCorrectly() {

		org.bson.Document document = new org.bson.Document();
		document.put("foo", "bar");

		org.bson.Document result = converter.read(org.bson.Document.class, document);

		assertThat(result).isEqualTo(document);
	}

	@Test // DATAMONGO-329
	public void writesMapAsGenericFieldCorrectly() {

		Map<String, A<String>> objectToSave = new HashMap<String, A<String>>();
		objectToSave.put("test", new A<String>("testValue"));

		A<Map<String, A<String>>> a = new A<Map<String, A<String>>>(objectToSave);
		org.bson.Document result = new org.bson.Document();

		converter.write(a, result);

		assertThat(result.get(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY)).isEqualTo(A.class.getName());
		assertThat(result.get("valueType")).isEqualTo(HashMap.class.getName());

		org.bson.Document object = (org.bson.Document) result.get("value");
		assertThat(object).isNotNull();

		org.bson.Document inner = (org.bson.Document) object.get("test");
		assertThat(inner).isNotNull();
		assertThat(inner.get(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY)).isEqualTo(A.class.getName());
		assertThat(inner.get("valueType")).isEqualTo(String.class.getName());
		assertThat(inner.get("value")).isEqualTo("testValue");
	}

	@Test
	public void writesIntIdCorrectly() {

		ClassWithIntId value = new ClassWithIntId();
		value.id = 5;

		org.bson.Document result = new org.bson.Document();
		converter.write(value, result);

		assertThat(result.get("_id")).isEqualTo(5);
	}

	@Test // DATAMONGO-368
	@SuppressWarnings("unchecked")
	public void writesNullValuesForCollection() {

		CollectionWrapper wrapper = new CollectionWrapper();
		wrapper.contacts = Arrays.asList(new Person(), null);

		org.bson.Document result = new org.bson.Document();
		converter.write(wrapper, result);

		Object contacts = result.get("contacts");
		assertThat(contacts).isInstanceOf(Collection.class);
		assertThat(((Collection<?>) contacts).size()).isEqualTo(2);
		assertThat((Collection<Object>) contacts).containsNull();
	}

	@Test // DATAMONGO-379
	public void considersDefaultingExpressionsAtConstructorArguments() {

		org.bson.Document document = new org.bson.Document("foo", "bar");
		document.put("foobar", 2.5);

		DefaultedConstructorArgument result = converter.read(DefaultedConstructorArgument.class, document);
		assertThat(result.bar).isEqualTo(-1);
	}

	@Test // DATAMONGO-379
	public void usesDocumentFieldIfReferencedInAtValue() {

		org.bson.Document document = new org.bson.Document("foo", "bar");
		document.put("something", 37);
		document.put("foobar", 2.5);

		DefaultedConstructorArgument result = converter.read(DefaultedConstructorArgument.class, document);
		assertThat(result.bar).isEqualTo(37);
	}

	@Test(expected = MappingInstantiationException.class) // DATAMONGO-379
	public void rejectsNotFoundConstructorParameterForPrimitiveType() {

		org.bson.Document document = new org.bson.Document("foo", "bar");

		converter.read(DefaultedConstructorArgument.class, document);
	}

	@Test // DATAMONGO-358
	public void writesListForObjectPropertyCorrectly() {

		Attribute attribute = new Attribute();
		attribute.key = "key";
		attribute.value = Arrays.asList("1", "2");

		Item item = new Item();
		item.attributes = Arrays.asList(attribute);

		org.bson.Document result = new org.bson.Document();

		converter.write(item, result);

		Item read = converter.read(Item.class, result);
		assertThat(read.attributes.size()).isEqualTo(1);
		assertThat(read.attributes.get(0).key).isEqualTo(attribute.key);
		assertThat(read.attributes.get(0).value).isInstanceOf(Collection.class);

		@SuppressWarnings("unchecked")
		Collection<String> values = (Collection<String>) read.attributes.get(0).value;

		assertThat(values.size()).isEqualTo(2);
		assertThat(values).contains("1", "2");
	}

	@Test // DATAMONGO-380
	public void rejectsMapWithKeyContainingDotsByDefault() {
		assertThatExceptionOfType(MappingException.class)
				.isThrownBy(() -> converter.write(Collections.singletonMap("foo.bar", "foobar"), new org.bson.Document()));
	}

	@Test // DATAMONGO-380
	public void escapesDotInMapKeysIfReplacementConfigured() {

		converter.setMapKeyDotReplacement("~");

		org.bson.Document document = new org.bson.Document();
		converter.write(Collections.singletonMap("foo.bar", "foobar"), document);

		assertThat((String) document.get("foo~bar")).isEqualTo("foobar");
		assertThat(document.containsKey("foo.bar")).isFalse();
	}

	@Test // DATAMONGO-380
	@SuppressWarnings("unchecked")
	public void unescapesDotInMapKeysIfReplacementConfigured() {

		converter.setMapKeyDotReplacement("~");

		org.bson.Document document = new org.bson.Document("foo~bar", "foobar");
		Map<String, String> result = converter.read(Map.class, document);

		assertThat(result.get("foo.bar")).isEqualTo("foobar");
		assertThat(result.containsKey("foobar")).isFalse();
	}

	@Test // DATAMONGO-382
	@Ignore("mongo3 - no longer supported")
	public void convertsSetToBasicDBList() {

		Address address = new Address();
		address.city = "London";
		address.street = "Foo";

		Object result = converter.convertToMongoType(Collections.singleton(address), ClassTypeInformation.OBJECT);
		assertThat(result).isInstanceOf(List.class);

		Set<?> readResult = converter.read(Set.class, (org.bson.Document) result);
		assertThat(readResult.size()).isEqualTo(1);
		assertThat(readResult.iterator().next()).isInstanceOf(Address.class);
	}

	@Test // DATAMONGO-402
	public void readsMemberClassCorrectly() {

		org.bson.Document document = new org.bson.Document("inner", new org.bson.Document("value", "FOO!"));

		Outer outer = converter.read(Outer.class, document);
		assertThat(outer.inner).isNotNull();
		assertThat(outer.inner.value).isEqualTo("FOO!");
		assertSyntheticFieldValueOf(outer.inner, outer);
	}

	@Test // DATAMONGO-458
	public void readEmptyCollectionIsModifiable() {

		org.bson.Document document = new org.bson.Document("contactsSet", new BasicDBList());
		CollectionWrapper wrapper = converter.read(CollectionWrapper.class, document);

		assertThat(wrapper.contactsSet).isNotNull();
		wrapper.contactsSet.add(new Contact() {});
	}

	@Test // DATAMONGO-424
	public void readsPlainDBRefObject() {

		DBRef dbRef = new DBRef("foo", 2);
		org.bson.Document document = new org.bson.Document("ref", dbRef);

		DBRefWrapper result = converter.read(DBRefWrapper.class, document);
		assertThat(result.ref).isEqualTo(dbRef);
	}

	@Test // DATAMONGO-424
	public void readsCollectionOfDBRefs() {

		DBRef dbRef = new DBRef("foo", 2);
		BasicDBList refs = new BasicDBList();
		refs.add(dbRef);

		org.bson.Document document = new org.bson.Document("refs", refs);

		DBRefWrapper result = converter.read(DBRefWrapper.class, document);
		assertThat(result.refs).hasSize(1);
		assertThat(result.refs).contains(dbRef);
	}

	@Test // DATAMONGO-424
	public void readsDBRefMap() {

		DBRef dbRef = mock(DBRef.class);
		org.bson.Document refMap = new org.bson.Document("foo", dbRef);
		org.bson.Document document = new org.bson.Document("refMap", refMap);

		DBRefWrapper result = converter.read(DBRefWrapper.class, document);

		assertThat(result.refMap.entrySet()).hasSize(1);
		assertThat(result.refMap.values()).contains(dbRef);
	}

	@Test // DATAMONGO-424
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void resolvesDBRefMapValue() {

		when(resolver.fetch(Mockito.any(DBRef.class))).thenReturn(new org.bson.Document());
		DBRef dbRef = mock(DBRef.class);

		org.bson.Document refMap = new org.bson.Document("foo", dbRef);
		org.bson.Document document = new org.bson.Document("personMap", refMap);

		DBRefWrapper result = converter.read(DBRefWrapper.class, document);

		assertThat(result.personMap.entrySet()).hasSize(1);
		assertThat(result.personMap.values()).anyMatch(Person.class::isInstance);
	}

	@Test // DATAMONGO-462
	public void writesURLsAsStringOutOfTheBox() throws Exception {

		URLWrapper wrapper = new URLWrapper();
		wrapper.url = new URL("https://springsource.org");
		org.bson.Document sink = new org.bson.Document();

		converter.write(wrapper, sink);

		assertThat(sink.get("url")).isEqualTo("https://springsource.org");
	}

	@Test // DATAMONGO-462
	public void readsURLFromStringOutOfTheBox() throws Exception {
		org.bson.Document document = new org.bson.Document("url", "https://springsource.org");
		URLWrapper result = converter.read(URLWrapper.class, document);
		assertThat(result.url).isEqualTo(new URL("https://springsource.org"));
	}

	@Test // DATAMONGO-485
	public void writesComplexIdCorrectly() {

		ComplexId id = new ComplexId();
		id.innerId = 4711L;

		ClassWithComplexId entity = new ClassWithComplexId();
		entity.complexId = id;

		org.bson.Document document = new org.bson.Document();
		converter.write(entity, document);

		Object idField = document.get("_id");
		assertThat(idField).isNotNull();
		assertThat(idField).isInstanceOf(org.bson.Document.class);
		assertThat(((org.bson.Document) idField).get("innerId")).isEqualTo(4711L);
	}

	@Test // DATAMONGO-485
	public void readsComplexIdCorrectly() {

		org.bson.Document innerId = new org.bson.Document("innerId", 4711L);
		org.bson.Document entity = new org.bson.Document("_id", innerId);

		ClassWithComplexId result = converter.read(ClassWithComplexId.class, entity);

		assertThat(result.complexId).isNotNull();
		assertThat(result.complexId.innerId).isEqualTo(4711L);
	}

	@Test // DATAMONGO-489
	public void readsArraysAsMapValuesCorrectly() {

		BasicDBList list = new BasicDBList();
		list.add("Foo");
		list.add("Bar");

		org.bson.Document map = new org.bson.Document("key", list);
		org.bson.Document wrapper = new org.bson.Document("mapOfStrings", map);

		ClassWithMapProperty result = converter.read(ClassWithMapProperty.class, wrapper);
		assertThat(result.mapOfStrings).isNotNull();

		String[] values = result.mapOfStrings.get("key");
		assertThat(values).isNotNull();
		assertThat(values).hasSize(2);
	}

	@Test // DATAMONGO-497
	public void readsEmptyCollectionIntoConstructorCorrectly() {

		org.bson.Document source = new org.bson.Document("attributes", new BasicDBList());

		TypWithCollectionConstructor result = converter.read(TypWithCollectionConstructor.class, source);
		assertThat(result.attributes).isNotNull();
	}

	private static void assertSyntheticFieldValueOf(Object target, Object expected) {

		for (int i = 0; i < 10; i++) {
			try {
				assertThat(ReflectionTestUtils.getField(target, "this$" + i)).isEqualTo(expected);
				return;
			} catch (IllegalArgumentException e) {
				// Suppress and try next
			}
		}

		fail(String.format("Didn't find synthetic field on %s!", target));
	}

	@Test // DATAMGONGO-508
	public void eagerlyReturnsDBRefObjectIfTargetAlreadyIsOne() {

		DBRef dbRef = new DBRef("collection", "id");

		MongoPersistentProperty property = mock(MongoPersistentProperty.class);

		assertThat(converter.createDBRef(dbRef, property)).isEqualTo(dbRef);
	}

	@Test // DATAMONGO-523, DATAMONGO-1509
	public void considersTypeAliasAnnotation() {

		Aliased aliased = new Aliased();
		aliased.name = "foo";

		org.bson.Document result = new org.bson.Document();
		converter.write(aliased, result);

		assertTypeHint(result, "_");
	}

	@Test // DATAMONGO-533
	public void marshalsThrowableCorrectly() {

		ThrowableWrapper wrapper = new ThrowableWrapper();
		wrapper.throwable = new Exception();

		org.bson.Document document = new org.bson.Document();
		converter.write(wrapper, document);
	}

	@Test // DATAMONGO-592
	public void recursivelyConvertsSpELReadValue() {

		org.bson.Document input = org.bson.Document.parse(
				"{ \"_id\" : { \"$oid\" : \"50ca271c4566a2b08f2d667a\" }, \"_class\" : \"com.recorder.TestRecorder2$ObjectContainer\", \"property\" : { \"property\" : 100 } }");

		converter.read(ObjectContainer.class, input);
	}

	@Test // DATAMONGO-724
	public void mappingConsidersCustomConvertersNotWritingTypeInformation() {

		Person person = new Person();
		person.firstname = "Dave";

		ClassWithMapProperty entity = new ClassWithMapProperty();
		entity.mapOfPersons = new HashMap<String, Person>();
		entity.mapOfPersons.put("foo", person);
		entity.mapOfObjects = new HashMap<String, Object>();
		entity.mapOfObjects.put("foo", person);

		CustomConversions conversions = new MongoCustomConversions(
				Arrays.asList(new Converter<Person, org.bson.Document>() {

					@Override
					public org.bson.Document convert(Person source) {
						return new org.bson.Document().append("firstname", source.firstname)//
								.append("_class", Person.class.getName());
					}

				}, new Converter<org.bson.Document, Person>() {

					@Override
					public Person convert(org.bson.Document source) {
						Person person = new Person();
						person.firstname = source.get("firstname").toString();
						person.lastname = "converter";
						return person;
					}
				}));

		MongoMappingContext context = new MongoMappingContext();
		context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
		context.afterPropertiesSet();

		MappingMongoConverter mongoConverter = new MappingMongoConverter(resolver, context);
		mongoConverter.setCustomConversions(conversions);
		mongoConverter.afterPropertiesSet();

		org.bson.Document document = new org.bson.Document();
		mongoConverter.write(entity, document);

		ClassWithMapProperty result = mongoConverter.read(ClassWithMapProperty.class, document);

		assertThat(result.mapOfPersons).isNotNull();
		Person personCandidate = result.mapOfPersons.get("foo");
		assertThat(personCandidate).isNotNull();
		assertThat(personCandidate.firstname).isEqualTo("Dave");

		assertThat(result.mapOfObjects).isNotNull();
		Object value = result.mapOfObjects.get("foo");
		assertThat(value).isNotNull();
		assertThat(value).isInstanceOf(Person.class);
		assertThat(((Person) value).firstname).isEqualTo("Dave");
		assertThat(((Person) value).lastname).isEqualTo("converter");
	}

	@Test // DATAMONGO-743, DATAMONGO-2198
	public void readsIntoStringsOutOfTheBox() {

		String target = converter.read(String.class, new org.bson.Document("firstname", "Dave"));

		assertThat(target).startsWith("{");
		assertThat(target).endsWith("}");
		assertThat(target).contains("\"firstname\"");
		assertThat(target).contains("\"Dave\"");
	}

	@Test // DATAMONGO-766
	public void writesProjectingTypeCorrectly() {

		NestedType nested = new NestedType();
		nested.c = "C";

		ProjectingType type = new ProjectingType();
		type.name = "name";
		type.foo = "bar";
		type.a = nested;

		org.bson.Document result = new org.bson.Document();
		converter.write(type, result);

		assertThat(result.get("name")).isEqualTo((Object) "name");
		org.bson.Document aValue = DocumentTestUtils.getAsDocument(result, "a");
		assertThat(aValue.get("b")).isEqualTo((Object) "bar");
		assertThat(aValue.get("c")).isEqualTo((Object) "C");
	}

	@Test // DATAMONGO-812, DATAMONGO-893, DATAMONGO-1509
	public void convertsListToBasicDBListAndRetainsTypeInformationForComplexObjects() {

		Address address = new Address();
		address.city = "London";
		address.street = "Foo";

		Object result = converter.convertToMongoType(Collections.singletonList(address),
				ClassTypeInformation.from(InterfaceType.class));

		assertThat(result).isInstanceOf(List.class);

		List<Object> dbList = (List<Object>) result;
		assertThat(dbList).hasSize(1);
		assertTypeHint(getAsDocument(dbList, 0), Address.class);
	}

	@Test // DATAMONGO-812
	public void convertsListToBasicDBListWithoutTypeInformationForSimpleTypes() {

		Object result = converter.convertToMongoType(Collections.singletonList("foo"));

		assertThat(result).isInstanceOf(List.class);

		List<Object> dbList = (List<Object>) result;
		assertThat(dbList).hasSize(1);
		assertThat(dbList.get(0)).isInstanceOf(String.class);
	}

	@Test // DATAMONGO-812, DATAMONGO-1509
	public void convertsArrayToBasicDBListAndRetainsTypeInformationForComplexObjects() {

		Address address = new Address();
		address.city = "London";
		address.street = "Foo";

		Object result = converter.convertToMongoType(new Address[] { address }, ClassTypeInformation.OBJECT);

		assertThat(result).isInstanceOf(List.class);

		List<Object> dbList = (List<Object>) result;
		assertThat(dbList).hasSize(1);
		assertTypeHint(getAsDocument(dbList, 0), Address.class);
	}

	@Test // DATAMONGO-812
	public void convertsArrayToBasicDBListWithoutTypeInformationForSimpleTypes() {

		Object result = converter.convertToMongoType(new String[] { "foo" });

		assertThat(result).isInstanceOf(List.class);

		List<Object> dbList = (List<Object>) result;
		assertThat(dbList).hasSize(1);
		assertThat(dbList.get(0)).isInstanceOf(String.class);
	}

	@Test // DATAMONGO-833
	public void readsEnumSetCorrectly() {

		BasicDBList enumSet = new BasicDBList();
		enumSet.add("SECOND");
		org.bson.Document document = new org.bson.Document("enumSet", enumSet);

		ClassWithEnumProperty result = converter.read(ClassWithEnumProperty.class, document);

		assertThat(result.enumSet).isInstanceOf(EnumSet.class);
		assertThat(result.enumSet.size()).isEqualTo(1);
		assertThat(result.enumSet).contains(SampleEnum.SECOND);
	}

	@Test // DATAMONGO-833
	public void readsEnumMapCorrectly() {

		org.bson.Document enumMap = new org.bson.Document("FIRST", "Dave");
		ClassWithEnumProperty result = converter.read(ClassWithEnumProperty.class,
				new org.bson.Document("enumMap", enumMap));

		assertThat(result.enumMap).isInstanceOf(EnumMap.class);
		assertThat(result.enumMap.size()).isEqualTo(1);
		assertThat(result.enumMap.get(SampleEnum.FIRST)).isEqualTo("Dave");
	}

	@Test // DATAMONGO-887
	public void readsTreeMapCorrectly() {

		org.bson.Document person = new org.bson.Document("foo", "Dave");
		org.bson.Document treeMapOfPerson = new org.bson.Document("key", person);
		org.bson.Document document = new org.bson.Document("treeMapOfPersons", treeMapOfPerson);

		ClassWithMapProperty result = converter.read(ClassWithMapProperty.class, document);

		assertThat(result.treeMapOfPersons).isNotNull();
		assertThat(result.treeMapOfPersons.get("key")).isNotNull();
		assertThat(result.treeMapOfPersons.get("key").firstname).isEqualTo("Dave");
	}

	@Test // DATAMONGO-887
	public void writesTreeMapCorrectly() {

		Person person = new Person();
		person.firstname = "Dave";

		ClassWithMapProperty source = new ClassWithMapProperty();
		source.treeMapOfPersons = new TreeMap<String, Person>();
		source.treeMapOfPersons.put("key", person);

		org.bson.Document result = new org.bson.Document();

		converter.write(source, result);

		org.bson.Document map = getAsDocument(result, "treeMapOfPersons");
		org.bson.Document entry = getAsDocument(map, "key");
		assertThat(entry.get("foo")).isEqualTo("Dave");
	}

	@Test // DATAMONGO-858
	public void shouldWriteEntityWithGeoBoxCorrectly() {

		ClassWithGeoBox object = new ClassWithGeoBox();
		object.box = new Box(new Point(1, 2), new Point(3, 4));

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		assertThat(document).isNotNull();
		assertThat(document.get("box")).isInstanceOf(org.bson.Document.class);
		assertThat(document.get("box")).isEqualTo((Object) new org.bson.Document()
				.append("first", toDocument(object.box.getFirst())).append("second", toDocument(object.box.getSecond())));
	}

	private static org.bson.Document toDocument(Point point) {
		return new org.bson.Document("x", point.getX()).append("y", point.getY());
	}

	@Test // DATAMONGO-858
	public void shouldReadEntityWithGeoBoxCorrectly() {

		ClassWithGeoBox object = new ClassWithGeoBox();
		object.box = new Box(new Point(1, 2), new Point(3, 4));

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		ClassWithGeoBox result = converter.read(ClassWithGeoBox.class, document);

		assertThat(result).isNotNull();
		assertThat(result.box).isEqualTo(object.box);
	}

	@Test // DATAMONGO-858
	public void shouldWriteEntityWithGeoPolygonCorrectly() {

		ClassWithGeoPolygon object = new ClassWithGeoPolygon();
		object.polygon = new Polygon(new Point(1, 2), new Point(3, 4), new Point(4, 5));

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		assertThat(document).isNotNull();

		assertThat(document.get("polygon")).isInstanceOf(org.bson.Document.class);
		org.bson.Document polygonDoc = (org.bson.Document) document.get("polygon");

		@SuppressWarnings("unchecked")
		List<org.bson.Document> points = (List<org.bson.Document>) polygonDoc.get("points");

		assertThat(points).hasSize(3);
		assertThat(points).contains(toDocument(object.polygon.getPoints().get(0)),
				toDocument(object.polygon.getPoints().get(1)), toDocument(object.polygon.getPoints().get(2)));
	}

	@Test // DATAMONGO-858
	public void shouldReadEntityWithGeoPolygonCorrectly() {

		ClassWithGeoPolygon object = new ClassWithGeoPolygon();
		object.polygon = new Polygon(new Point(1, 2), new Point(3, 4), new Point(4, 5));

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		ClassWithGeoPolygon result = converter.read(ClassWithGeoPolygon.class, document);

		assertThat(result).isNotNull();
		assertThat(result.polygon).isEqualTo(object.polygon);
	}

	@Test // DATAMONGO-858
	public void shouldWriteEntityWithGeoCircleCorrectly() {

		ClassWithGeoCircle object = new ClassWithGeoCircle();
		Circle circle = new Circle(new Point(1, 2), 3);
		Distance radius = circle.getRadius();
		object.circle = circle;

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		assertThat(document).isNotNull();
		assertThat(document.get("circle")).isInstanceOf(org.bson.Document.class);
		assertThat(document.get("circle")).isEqualTo((Object) new org.bson.Document("center",
				new org.bson.Document("x", circle.getCenter().getX()).append("y", circle.getCenter().getY()))
						.append("radius", radius.getNormalizedValue()).append("metric", radius.getMetric().toString()));
	}

	@Test // DATAMONGO-858
	public void shouldReadEntityWithGeoCircleCorrectly() {

		ClassWithGeoCircle object = new ClassWithGeoCircle();
		object.circle = new Circle(new Point(1, 2), 3);

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		ClassWithGeoCircle result = converter.read(ClassWithGeoCircle.class, document);

		assertThat(result).isNotNull();
		assertThat(result.circle).isEqualTo(result.circle);
	}

	@Test // DATAMONGO-858
	public void shouldWriteEntityWithGeoSphereCorrectly() {

		ClassWithGeoSphere object = new ClassWithGeoSphere();
		Sphere sphere = new Sphere(new Point(1, 2), 3);
		Distance radius = sphere.getRadius();
		object.sphere = sphere;

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		assertThat(document).isNotNull();
		assertThat(document.get("sphere")).isInstanceOf(org.bson.Document.class);
		assertThat(document.get("sphere")).isEqualTo((Object) new org.bson.Document("center",
				new org.bson.Document("x", sphere.getCenter().getX()).append("y", sphere.getCenter().getY()))
						.append("radius", radius.getNormalizedValue()).append("metric", radius.getMetric().toString()));
	}

	@Test // DATAMONGO-858
	public void shouldWriteEntityWithGeoSphereWithMetricDistanceCorrectly() {

		ClassWithGeoSphere object = new ClassWithGeoSphere();
		Sphere sphere = new Sphere(new Point(1, 2), new Distance(3, Metrics.KILOMETERS));
		Distance radius = sphere.getRadius();
		object.sphere = sphere;

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		assertThat(document).isNotNull();
		assertThat(document.get("sphere")).isInstanceOf(org.bson.Document.class);
		assertThat(document.get("sphere")).isEqualTo((Object) new org.bson.Document("center",
				new org.bson.Document("x", sphere.getCenter().getX()).append("y", sphere.getCenter().getY()))
						.append("radius", radius.getNormalizedValue()).append("metric", radius.getMetric().toString()));
	}

	@Test // DATAMONGO-858
	public void shouldReadEntityWithGeoSphereCorrectly() {

		ClassWithGeoSphere object = new ClassWithGeoSphere();
		object.sphere = new Sphere(new Point(1, 2), 3);

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		ClassWithGeoSphere result = converter.read(ClassWithGeoSphere.class, document);

		assertThat(result).isNotNull();
		assertThat(result.sphere).isEqualTo(object.sphere);
	}

	@Test // DATAMONGO-858
	public void shouldWriteEntityWithGeoShapeCorrectly() {

		ClassWithGeoShape object = new ClassWithGeoShape();
		Sphere sphere = new Sphere(new Point(1, 2), 3);
		Distance radius = sphere.getRadius();
		object.shape = sphere;

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		assertThat(document).isNotNull();
		assertThat(document.get("shape")).isInstanceOf(org.bson.Document.class);
		assertThat(document.get("shape")).isEqualTo((Object) new org.bson.Document("center",
				new org.bson.Document("x", sphere.getCenter().getX()).append("y", sphere.getCenter().getY()))
						.append("radius", radius.getNormalizedValue()).append("metric", radius.getMetric().toString()));
	}

	@Test // DATAMONGO-858
	@Ignore
	public void shouldReadEntityWithGeoShapeCorrectly() {

		ClassWithGeoShape object = new ClassWithGeoShape();
		Sphere sphere = new Sphere(new Point(1, 2), 3);
		object.shape = sphere;

		org.bson.Document document = new org.bson.Document();
		converter.write(object, document);

		ClassWithGeoShape result = converter.read(ClassWithGeoShape.class, document);

		assertThat(result).isNotNull();
		assertThat(result.shape).isEqualTo(sphere);
	}

	@Test // DATAMONGO-976
	public void shouldIgnoreTextScorePropertyWhenWriting() {

		ClassWithTextScoreProperty source = new ClassWithTextScoreProperty();
		source.score = Float.MAX_VALUE;

		org.bson.Document document = new org.bson.Document();
		converter.write(source, document);

		assertThat(document.get("score")).isNull();
	}

	@Test // DATAMONGO-976
	public void shouldIncludeTextScorePropertyWhenReading() {

		ClassWithTextScoreProperty entity = converter.read(ClassWithTextScoreProperty.class,
				new org.bson.Document("score", 5F));
		assertThat(entity.score).isEqualTo(5F);
	}

	@Test // DATAMONGO-1001, DATAMONGO-1509
	public void shouldWriteCglibProxiedClassTypeInformationCorrectly() {

		ProxyFactory factory = new ProxyFactory();
		factory.setTargetClass(GenericType.class);
		factory.setProxyTargetClass(true);

		GenericType<?> proxied = (GenericType<?>) factory.getProxy();
		org.bson.Document document = new org.bson.Document();
		converter.write(proxied, document);

		assertTypeHint(document, GenericType.class);
	}

	@Test // DATAMONGO-1001
	public void shouldUseTargetObjectOfLazyLoadingProxyWhenWriting() {

		LazyLoadingProxy mock = mock(LazyLoadingProxy.class);

		org.bson.Document document = new org.bson.Document();
		converter.write(mock, document);

		verify(mock, times(1)).getTarget();
	}

	@Test // DATAMONGO-1034
	public void rejectsBasicDbListToBeConvertedIntoComplexType() {

		List<Object> inner = new ArrayList<Object>();
		inner.add("key");
		inner.add("value");

		List<Object> outer = new ArrayList<Object>();
		outer.add(inner);
		outer.add(inner);

		org.bson.Document source = new org.bson.Document("attributes", outer);

		assertThatExceptionOfType(MappingException.class).isThrownBy(() -> converter.read(Item.class, source));
	}

	@Test // DATAMONGO-1058
	public void readShouldRespectExplicitFieldNameForDbRef() {

		org.bson.Document source = new org.bson.Document();
		source.append("explict-name-for-db-ref", new DBRef("foo", "1"));

		converter.read(ClassWithExplicitlyNamedDBRefProperty.class, source);

		verify(resolver, times(1)).resolveDbRef(Mockito.any(MongoPersistentProperty.class), Mockito.any(DBRef.class),
				Mockito.any(DbRefResolverCallback.class), Mockito.any(DbRefProxyHandler.class));
	}

	@Test // DATAMONGO-1050
	public void writeShouldUseExplicitFieldnameForIdPropertyWhenAnnotated() {

		RootForClassWithExplicitlyRenamedIdField source = new RootForClassWithExplicitlyRenamedIdField();
		source.id = "rootId";
		source.nested = new ClassWithExplicitlyRenamedField();
		source.nested.id = "nestedId";

		org.bson.Document sink = new org.bson.Document();
		converter.write(source, sink);

		assertThat(sink.get("_id")).isEqualTo("rootId");
		assertThat(sink.get("nested")).isEqualTo(new org.bson.Document().append("id", "nestedId"));
	}

	@Test // DATAMONGO-1050
	public void readShouldUseExplicitFieldnameForIdPropertyWhenAnnotated() {

		org.bson.Document source = new org.bson.Document().append("_id", "rootId").append("nested",
				new org.bson.Document("id", "nestedId"));

		RootForClassWithExplicitlyRenamedIdField sink = converter.read(RootForClassWithExplicitlyRenamedIdField.class,
				source);

		assertThat(sink.id).isEqualTo("rootId");
		assertThat(sink.nested).isNotNull();
		assertThat(sink.nested.id).isEqualTo("nestedId");
	}

	@Test // DATAMONGO-1050
	public void namedIdFieldShouldExtractValueFromUnderscoreIdField() {

		org.bson.Document document = new org.bson.Document().append("_id", "A").append("id", "B");

		ClassWithNamedIdField withNamedIdField = converter.read(ClassWithNamedIdField.class, document);

		assertThat(withNamedIdField.id).isEqualTo("A");
	}

	@Test // DATAMONGO-1050
	public void explicitlyRenamedIfFieldShouldExtractValueFromIdField() {

		org.bson.Document document = new org.bson.Document().append("_id", "A").append("id", "B");

		ClassWithExplicitlyRenamedField withExplicitlyRenamedField = converter.read(ClassWithExplicitlyRenamedField.class,
				document);

		assertThat(withExplicitlyRenamedField.id).isEqualTo("B");
	}

	@Test // DATAMONGO-1050
	public void annotatedIdFieldShouldExtractValueFromUnderscoreIdField() {

		org.bson.Document document = new org.bson.Document().append("_id", "A").append("id", "B");

		ClassWithAnnotatedIdField withAnnotatedIdField = converter.read(ClassWithAnnotatedIdField.class, document);

		assertThat(withAnnotatedIdField.key).isEqualTo("A");
	}

	@Test // DATAMONGO-1102
	public void convertsJava8DateTimeTypesToDateAndBack() {

		TypeWithLocalDateTime source = new TypeWithLocalDateTime();
		LocalDateTime reference = source.date;
		org.bson.Document result = new org.bson.Document();

		converter.write(source, result);

		assertThat(result.get("date")).isInstanceOf(Date.class);
		assertThat(converter.read(TypeWithLocalDateTime.class, result).date).isEqualTo(reference);
	}

	@Test // DATAMONGO-1128
	public void writesOptionalsCorrectly() {

		TypeWithOptional type = new TypeWithOptional();
		type.localDateTime = Optional.of(LocalDateTime.now());

		org.bson.Document result = new org.bson.Document();

		converter.write(type, result);

		assertThat(getAsDocument(result, "string")).isEqualTo(new org.bson.Document());

		org.bson.Document localDateTime = getAsDocument(result, "localDateTime");
		assertThat(localDateTime.get("value")).isInstanceOf(Date.class);
	}

	@Test // DATAMONGO-1128
	@Ignore("Broken by DATAMONGO-1992 - In fact, storing Optional fields seems an anti-pattern.")
	public void readsOptionalsCorrectly() {

		LocalDateTime now = LocalDateTime.now();
		Date reference = Date.from(now.atZone(systemDefault()).toInstant());

		org.bson.Document optionalOfLocalDateTime = new org.bson.Document("value", reference);
		org.bson.Document result = new org.bson.Document("localDateTime", optionalOfLocalDateTime);

		TypeWithOptional read = converter.read(TypeWithOptional.class, result);

		assertThat(read.string).isEmpty();
		assertThat(read.localDateTime).isEqualTo(Optional.of(now));
	}

	@Test // DATAMONGO-1118
	public void convertsMapKeyUsingCustomConverterForAndBackwards() {

		MappingMongoConverter converter = new MappingMongoConverter(resolver, mappingContext);
		converter.setCustomConversions(
				new MongoCustomConversions(Arrays.asList(new FooBarEnumToStringConverter(), new StringToFooNumConverter())));
		converter.afterPropertiesSet();

		ClassWithMapUsingEnumAsKey source = new ClassWithMapUsingEnumAsKey();
		source.map = new HashMap<FooBarEnum, String>();
		source.map.put(FooBarEnum.FOO, "wohoo");

		org.bson.Document target = new org.bson.Document();
		converter.write(source, target);

		assertThat(converter.read(ClassWithMapUsingEnumAsKey.class, target).map).isEqualTo(source.map);
	}

	@Test // DATAMONGO-1118
	public void writesMapKeyUsingCustomConverter() {

		MappingMongoConverter converter = new MappingMongoConverter(resolver, mappingContext);
		converter.setCustomConversions(new MongoCustomConversions(Arrays.asList(new FooBarEnumToStringConverter())));
		converter.afterPropertiesSet();

		ClassWithMapUsingEnumAsKey source = new ClassWithMapUsingEnumAsKey();
		source.map = new HashMap<FooBarEnum, String>();
		source.map.put(FooBarEnum.FOO, "spring");
		source.map.put(FooBarEnum.BAR, "data");

		org.bson.Document target = new org.bson.Document();
		converter.write(source, target);

		org.bson.Document map = DocumentTestUtils.getAsDocument(target, "map");

		assertThat(map.containsKey("foo-enum-value")).isTrue();
		assertThat(map.containsKey("bar-enum-value")).isTrue();
	}

	@Test // DATAMONGO-1118
	public void readsMapKeyUsingCustomConverter() {

		MappingMongoConverter converter = new MappingMongoConverter(resolver, mappingContext);
		converter.setCustomConversions(new MongoCustomConversions(Arrays.asList(new StringToFooNumConverter())));
		converter.afterPropertiesSet();

		org.bson.Document source = new org.bson.Document("map", new org.bson.Document("foo-enum-value", "spring"));

		ClassWithMapUsingEnumAsKey target = converter.read(ClassWithMapUsingEnumAsKey.class, source);

		assertThat(target.map.get(FooBarEnum.FOO)).isEqualTo("spring");
	}

	@Test // DATAMONGO-1471
	public void readsDocumentWithPrimitiveIdButNoValue() {
		assertThat(converter.read(ClassWithIntId.class, new org.bson.Document())).isNotNull();
	}

	@Test // DATAMONGO-1497
	public void readsPropertyFromNestedFieldCorrectly() {

		org.bson.Document source = new org.bson.Document("nested", new org.bson.Document("sample", "value"));
		TypeWithPropertyInNestedField result = converter.read(TypeWithPropertyInNestedField.class, source);

		assertThat(result.sample).isEqualTo("value");
	}

	@Test // DATAMONGO-1525
	public void readsEmptyEnumSet() {

		org.bson.Document source = new org.bson.Document("enumSet", Collections.emptyList());

		assertThat(converter.read(ClassWithEnumProperty.class, source).enumSet).isEqualTo(EnumSet.noneOf(SampleEnum.class));
	}

	@Test // DATAMONGO-1757
	public void failsReadingDocumentIntoSimpleType() {

		org.bson.Document nested = new org.bson.Document("key", "value");
		org.bson.Document source = new org.bson.Document("map", new org.bson.Document("key", nested));

		assertThatExceptionOfType(MappingException.class)
				.isThrownBy(() -> converter.read(TypeWithMapOfLongValues.class, source));
	}

	@Test // DATAMONGO-1831
	public void shouldConvertArrayInConstructorCorrectly() {

		org.bson.Document source = new org.bson.Document("array", Collections.emptyList());

		assertThat(converter.read(WithArrayInConstructor.class, source).array).isEmpty();
	}

	@Test // DATAMONGO-1831
	public void shouldConvertNullForArrayInConstructorCorrectly() {

		org.bson.Document source = new org.bson.Document();

		assertThat(converter.read(WithArrayInConstructor.class, source).array).isNull();
	}

	@Test // DATAMONGO-1898
	public void writesInterfaceBackedEnumsToSimpleNameByDefault() {

		org.bson.Document document = new org.bson.Document();

		DocWithInterfacedEnum source = new DocWithInterfacedEnum();
		source.property = InterfacedEnum.INSTANCE;

		converter.write(source, document);

		assertThat(document) //
				.hasSize(2) //
				.hasEntrySatisfying("_class", __ -> {}) //
				.hasEntrySatisfying("property", value -> InterfacedEnum.INSTANCE.name().equals(value));
	}

	@Test // DATAMONGO-1898
	public void rejectsConversionFromStringToEnumBackedInterface() {

		org.bson.Document document = new org.bson.Document("property", InterfacedEnum.INSTANCE.name());

		assertThatExceptionOfType(ConverterNotFoundException.class) //
				.isThrownBy(() -> converter.read(DocWithInterfacedEnum.class, document));
	}

	@Test // DATAMONGO-1898
	public void readsInterfacedEnumIfConverterIsRegistered() {

		org.bson.Document document = new org.bson.Document("property", InterfacedEnum.INSTANCE.name());

		Converter<String, SomeInterface> enumConverter = new Converter<String, SomeInterface>() {

			@Override
			public SomeInterface convert(String source) {
				return InterfacedEnum.valueOf(source);
			}
		};

		converter.setCustomConversions(new MongoCustomConversions(Arrays.asList(enumConverter)));
		converter.afterPropertiesSet();

		DocWithInterfacedEnum result = converter.read(DocWithInterfacedEnum.class, document);

		assertThat(result.property).isEqualTo(InterfacedEnum.INSTANCE);
	}

	@Test // DATAMONGO-1904
	public void readsNestedArraysCorrectly() {

		List<List<List<Float>>> floats = Arrays.asList(Arrays.asList(Arrays.asList(1.0f, 2.0f)));

		org.bson.Document document = new org.bson.Document("nestedFloats", floats);

		WithNestedLists result = converter.read(WithNestedLists.class, document);

		assertThat(result.nestedFloats).hasSize(1);
		assertThat(result.nestedFloats).isEqualTo(new float[][][] { { { 1.0f, 2.0f } } });
	}

	@Test // DATAMONGO-1992
	public void readsImmutableObjectCorrectly() {

		org.bson.Document document = new org.bson.Document("_id", "foo");

		ImmutableObject result = converter.read(ImmutableObject.class, document);

		assertThat(result.id).isEqualTo("foo");
		assertThat(result.witherUsed).isTrue();
	}

	@Test // DATAMONGO-2026
	public void readsImmutableObjectWithConstructorIdPropertyCorrectly() {

		org.bson.Document source = new org.bson.Document("_id", "spring").append("value", "data");

		ImmutableObjectWithIdConstructorPropertyAndNoIdWitherMethod target = converter
				.read(ImmutableObjectWithIdConstructorPropertyAndNoIdWitherMethod.class, source);

		assertThat(target.id).isEqualTo("spring");
		assertThat(target.value).isEqualTo("data");
	}

	@Test // DATAMONGO-2011
	public void readsNestedListsToObjectCorrectly() {

		List<String> values = Arrays.asList("ONE", "TWO");
		org.bson.Document source = new org.bson.Document("value", Collections.singletonList(values));

		assertThat(converter.read(Attribute.class, source).value).isInstanceOf(List.class);
	}

	@Test // DATAMONGO-2043
	public void omitsTypeHintWhenWritingSimpleTypes() {

		org.bson.Document target = new org.bson.Document();
		converter.write(new org.bson.Document("value", "FitzChivalry"), target);

		assertThat(target).doesNotContainKeys("_class");
	}

	@Test // DATAMONGO-1798
	public void convertStringIdThatIsAnObjectIdHexToObjectIdIfTargetIsObjectId() {

		ObjectId source = new ObjectId();
		assertThat(converter.convertId(source.toHexString(), ObjectId.class)).isEqualTo(source);
	}

	@Test // DATAMONGO-1798
	public void donNotConvertStringIdThatIsAnObjectIdHexToObjectIdIfTargetIsString() {

		ObjectId source = new ObjectId();
		assertThat(converter.convertId(source.toHexString(), String.class)).isEqualTo(source.toHexString());
	}

	@Test // DATAMONGO-1798
	public void donNotConvertStringIdThatIsAnObjectIdHexToObjectIdIfTargetIsObject() {

		ObjectId source = new ObjectId();
		assertThat(converter.convertId(source.toHexString(), Object.class)).isEqualTo(source.toHexString());
	}

	@Test // DATAMONGO-2135
	public void addsEqualObjectsToCollection() {

		org.bson.Document itemDocument = new org.bson.Document("itemKey", "123");
		org.bson.Document orderDocument = new org.bson.Document("items",
				Arrays.asList(itemDocument, itemDocument, itemDocument));

		Order order = converter.read(Order.class, orderDocument);

		assertThat(order.items).hasSize(3);
	}

	@Test // DATAMONGO-1849
	public void mapsValueToExplicitTargetType() {

		WithExplicitTargetTypes source = new WithExplicitTargetTypes();
		source.script = "if (a > b) a else b";

		org.bson.Document target = new org.bson.Document();
		converter.write(source, target);

		assertThat(target.get("script")).isEqualTo(new Code(source.script));
	}

	@Test // DATAMONGO-1849
	public void mapsCollectionValueToExplicitTargetType() {

		String script = "if (a > b) a else b";
		WithExplicitTargetTypes source = new WithExplicitTargetTypes();
		source.scripts = Collections.singletonList(script);

		org.bson.Document target = new org.bson.Document();
		converter.write(source, target);

		assertThat(target.get("scripts", List.class)).containsExactly(new Code(script));
	}

	@Test // DATAMONGO-1849
	public void mapsBigDecimalToDecimal128WhenAnnotatedWithFieldTargetType() {

		WithExplicitTargetTypes source = new WithExplicitTargetTypes();
		source.bigDecimal = BigDecimal.valueOf(3.14159D);

		org.bson.Document target = new org.bson.Document();
		converter.write(source, target);

		assertThat(target.get("bigDecimal")).isEqualTo(new Decimal128(source.bigDecimal));
	}

	static class GenericType<T> {
		T content;
	}

	static class ClassWithEnumProperty {

		SampleEnum sampleEnum;
		List<SampleEnum> enums;
		EnumSet<SampleEnum> enumSet;
		EnumMap<SampleEnum, String> enumMap;
	}

	enum SampleEnum {
		FIRST {
			@Override
			void method() {}
		},
		SECOND {
			@Override
			void method() {

			}
		};

		abstract void method();
	}

	interface InterfaceType {

	}

	static class Address implements InterfaceType {
		String street;
		String city;
	}

	interface Contact {

	}

	static class Person implements Contact {

		@Id String id;

		LocalDate birthDate;

		@Field("foo") String firstname;
		String lastname;

		Set<Address> addresses;

		public Person() {

		}

		@PersistenceConstructor
		public Person(Set<Address> addresses) {
			this.addresses = addresses;
		}
	}

	static class ClassWithSortedMap {
		SortedMap<String, String> map;
	}

	static class ClassWithMapProperty {
		Map<Locale, String> map;
		Map<String, List<String>> mapOfLists;
		Map<String, Object> mapOfObjects;
		Map<String, String[]> mapOfStrings;
		Map<String, Person> mapOfPersons;
		TreeMap<String, Person> treeMapOfPersons;
	}

	static class ClassWithNestedMaps {
		Map<String, Map<String, Map<String, String>>> nestedMaps;
	}

	static class BirthDateContainer {
		LocalDate birthDate;
	}

	static class BigDecimalContainer {
		BigDecimal value;
		Map<String, BigDecimal> map;
		List<BigDecimal> collection;
	}

	static class CollectionWrapper {
		List<Contact> contacts;
		List<List<String>> strings;
		List<Map<String, Locale>> listOfMaps;
		Set<Contact> contactsSet;
	}

	static class LocaleWrapper {
		Locale locale;
	}

	static class ClassWithBigIntegerId {
		@Id BigInteger id;
	}

	static class A<T> {

		String valueType;
		T value;

		public A(T value) {
			this.valueType = value.getClass().getName();
			this.value = value;
		}
	}

	static class ClassWithIntId {

		@Id int id;
	}

	static class DefaultedConstructorArgument {

		String foo;
		int bar;
		double foobar;

		DefaultedConstructorArgument(String foo, @Value("#root.something ?: -1") int bar, double foobar) {
			this.foo = foo;
			this.bar = bar;
			this.foobar = foobar;
		}
	}

	static class Item {
		List<Attribute> attributes;
	}

	static class Attribute {
		String key;
		Object value;
	}

	static class Outer {

		class Inner {
			String value;
		}

		Inner inner;
	}

	static class DBRefWrapper {

		DBRef ref;
		List<DBRef> refs;
		Map<String, DBRef> refMap;
		Map<String, Person> personMap;
	}

	static class URLWrapper {
		URL url;
	}

	static class ClassWithComplexId {

		@Id ComplexId complexId;
	}

	static class ComplexId {
		Long innerId;
	}

	static class TypWithCollectionConstructor {

		List<Attribute> attributes;

		public TypWithCollectionConstructor(List<Attribute> attributes) {
			this.attributes = attributes;
		}
	}

	@TypeAlias("_")
	static class Aliased {
		String name;
	}

	static class ThrowableWrapper {

		Throwable throwable;
	}

	@Document
	static class PrimitiveContainer {

		@Field("property") private int m_property;

		@PersistenceConstructor
		public PrimitiveContainer(@Value("#root.property") int a_property) {
			m_property = a_property;
		}

		public int property() {
			return m_property;
		}
	}

	@Document
	static class ObjectContainer {

		@Field("property") private PrimitiveContainer m_property;

		@PersistenceConstructor
		public ObjectContainer(@Value("#root.property") PrimitiveContainer a_property) {
			m_property = a_property;
		}

		public PrimitiveContainer property() {
			return m_property;
		}
	}

	class ClassWithGeoBox {

		Box box;
	}

	class ClassWithGeoCircle {

		Circle circle;
	}

	class ClassWithGeoSphere {

		Sphere sphere;
	}

	class ClassWithGeoPolygon {

		Polygon polygon;
	}

	class ClassWithGeoShape {

		Shape shape;
	}

	class ClassWithTextScoreProperty {

		@TextScore Float score;
	}

	class ClassWithExplicitlyNamedDBRefProperty {

		@Field("explict-name-for-db-ref") //
		@org.springframework.data.mongodb.core.mapping.DBRef //
		ClassWithIntId dbRefProperty;

		public ClassWithIntId getDbRefProperty() {
			return dbRefProperty;
		}
	}

	static class RootForClassWithExplicitlyRenamedIdField {

		@Id String id;
		ClassWithExplicitlyRenamedField nested;
	}

	static class ClassWithExplicitlyRenamedField {

		@Field("id") String id;
	}

	static class RootForClassWithNamedIdField {

		String id;
		ClassWithNamedIdField nested;
	}

	static class ClassWithNamedIdField {

		String id;
	}

	static class ClassWithAnnotatedIdField {

		@Id String key;
	}

	static class TypeWithLocalDateTime {

		LocalDateTime date;

		TypeWithLocalDateTime() {
			this.date = LocalDateTime.now();
		}
	}

	static class TypeWithOptional {

		Optional<String> string = Optional.empty();
		Optional<LocalDateTime> localDateTime = Optional.empty();
	}

	static class ClassWithMapUsingEnumAsKey {

		enum FooBarEnum {
			FOO, BAR
		}

		Map<FooBarEnum, String> map;
	}

	@WritingConverter
	static class FooBarEnumToStringConverter implements Converter<FooBarEnum, String> {

		@Override
		public String convert(FooBarEnum source) {

			if (source == null) {
				return null;
			}

			return FooBarEnum.FOO.equals(source) ? "foo-enum-value" : "bar-enum-value";
		}
	}

	@ReadingConverter
	static class StringToFooNumConverter implements Converter<String, FooBarEnum> {

		@Override
		public FooBarEnum convert(String source) {

			if (source == null) {
				return null;
			}

			if ("foo-enum-value".equals(source)) {
				return FooBarEnum.FOO;
			}
			if ("bar-enum-value".equals(source)) {
				return FooBarEnum.BAR;
			}

			throw new ConversionNotSupportedException(source, String.class, null);
		}
	}

	static class TypeWithPropertyInNestedField {
		@Field("nested.sample") String sample;
	}

	static class TypeWithMapOfLongValues {
		Map<String, Long> map;
	}

	@RequiredArgsConstructor
	static class WithArrayInConstructor {

		final String[] array;

	}

	// DATAMONGO-1898

	// DATACMNS-1278
	static interface SomeInterface {}

	static enum InterfacedEnum implements SomeInterface {
		INSTANCE;
	}

	static class DocWithInterfacedEnum {
		SomeInterface property;
	}

	// DATAMONGO-1904

	static class WithNestedLists {
		float[][][] nestedFloats;
	}

	static class ImmutableObject {

		final String id;
		final String name;
		final boolean witherUsed;

		private ImmutableObject(String id) {
			this.id = id;
			this.name = null;
			this.witherUsed = false;
		}

		private ImmutableObject(String id, String name, boolean witherUsed) {
			this.id = id;
			this.name = name;
			this.witherUsed = witherUsed;
		}

		public ImmutableObject() {
			this.id = null;
			this.name = null;
			witherUsed = false;
		}

		public ImmutableObject withId(String id) {
			return new ImmutableObject(id, name, true);
		}

		public String getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		public boolean isWitherUsed() {
			return witherUsed;
		}
	}

	@RequiredArgsConstructor
	static class ImmutableObjectWithIdConstructorPropertyAndNoIdWitherMethod {

		final @Id String id;
		String value;
	}

	// DATAMONGO-2135

	@EqualsAndHashCode // equality check by fields
	static class SomeItem {
		String itemKey;
	}

	static class Order {
		Collection<SomeItem> items = new ArrayList<>();
	}

	static class WithExplicitTargetTypes {

		@Field(targetType = FieldType.SCRIPT) //
		String script;

		@Field(targetType = FieldType.SCRIPT) //
		List<String> scripts;

		@Field(targetType = FieldType.DECIMAL128) BigDecimal bigDecimal;
	}

}
