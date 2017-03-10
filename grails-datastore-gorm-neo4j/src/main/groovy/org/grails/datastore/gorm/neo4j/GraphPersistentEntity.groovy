package org.grails.datastore.gorm.neo4j

import groovy.transform.CompileStatic
import org.grails.datastore.gorm.neo4j.identity.SnowflakeIdGenerator
import org.grails.datastore.gorm.neo4j.mapping.config.NodeConfig
import org.grails.datastore.mapping.model.AbstractPersistentEntity
import org.grails.datastore.mapping.model.ClassMapping
import org.grails.datastore.mapping.model.DatastoreConfigurationException
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.config.GormMappingConfigurationStrategy
import org.grails.datastore.mapping.model.config.GormProperties
import org.springframework.util.ClassUtils

/**
 * Represents an entity mapped to the Neo4j graph, adding support for dynamic labelling
 *
 * @author Stefan Armbruster
 * @author Graeme Rocher
 *
 * @since 1.0
 *
 */
@CompileStatic
 class GraphPersistentEntity extends AbstractPersistentEntity<NodeConfig> {

    public static final String LABEL_SEPARATOR = ':'
    protected final NodeConfig mappedForm
    protected final Collection<String> staticLabels = []
    protected Collection<Object> labelObjects
    protected final boolean hasDynamicLabels
    protected final boolean hasDynamicAssociations
    protected final boolean relationshipEntity
    protected final GraphClassMapping classMapping
    protected IdGenerator idGenerator
    protected IdGenerator.Type idGeneratorType
    protected boolean assignedId = false
    protected boolean nativeId = false
    protected String identifierProperty = GormProperties.IDENTITY

    GraphPersistentEntity(Class javaClass, MappingContext context) {
        this(javaClass, context, false)
    }

    GraphPersistentEntity(Class javaClass, MappingContext context, boolean external) {
        super(javaClass, context)
        if(isExternal()) {
            this.mappedForm = null
        }
        else {
            this.mappedForm = (NodeConfig) context.getMappingFactory().createMappedForm(this)
        }
        this.relationshipEntity = this instanceof RelationshipPersistentEntity
        this.hasDynamicAssociations = mappedForm.isDynamicAssociations()
        this.hasDynamicLabels = establishLabels()
        this.external = external
        this.classMapping = new GraphClassMapping(this, context)
    }

    @Override
    void initialize() {
        super.initialize()
        if(!isExternal()) {
            def identity = getIdentity()
            if(identity != null) {
                def generatorType = identity.getMapping().getMappedForm().getGenerator()
                this.idGenerator = createIdGenerator(generatorType)
            }
        }
    }

    boolean isRelationshipEntity() {
        return relationshipEntity
    }

    protected IdGenerator createIdGenerator(String generatorType) {
        try {
            IdGenerator.Type type = generatorType == null ? IdGenerator.Type.NATIVE : IdGenerator.Type.valueOf(generatorType.toUpperCase())
            idGeneratorType = type

            switch(type) {
                case IdGenerator.Type.NATIVE:
                    // for the native generator use null to indicate that generation requires an insert
                    nativeId = true
                    return null
                case IdGenerator.Type.ASSIGNED:
                    assignedId = true
                    getIdentity().getMapping().getMappedForm().setUnique(true)
                    return null
                case IdGenerator.Type.SNOWFLAKE:
                    return ((Neo4jMappingContext)mappingContext).getIdGenerator()
                default:
                    def generator = ((Neo4jMappingContext) mappingContext).getIdGenerator()
                    if(generator == null) {
                        nativeId = true
                    }
                    return generator
            }
        } catch (IllegalArgumentException e) {
            try {
                def generatorClass = ClassUtils.forName(generatorType)
                if(IdGenerator.isAssignableFrom(generatorClass)) {
                    return (IdGenerator)generatorClass.newInstance()
                }
                else {
                    throw new DatastoreConfigurationException("Entity $javaClass defines an invalid id generator [$generatorClass]. The class must implement the IdGenerator interface")
                }
            } catch (Throwable e2) {
                def types = IdGenerator.Type.values().collect() { Enum en -> "'${en.name()}'" }.join(',')
                throw new DatastoreConfigurationException("Entity $javaClass defines an invalid id generator [$generatorType]. Should be one of ${types} or a class that implements the IdGenerator interface",e2 )
            }
        }
    }

    @Override
    ClassMapping<NodeConfig> getMapping() {
        return this.classMapping
    }

    /**
     * @return The ID generator to use
     */
    IdGenerator getIdGenerator() {
        return idGenerator
    }

    /**
     * @return The ID generator type
     */
    IdGenerator.Type getIdGeneratorType() {
        return idGeneratorType
    }

    /**
     * @return Whether the ID is assigned
     */
    boolean isAssignedId() {
        return assignedId
    }

    /**
     * Format a reference to the ID for cypher queries
     * @param variable The name of the variable for the id
     * @return The formatted id
     */
    String formatId(String variable = CypherBuilder.NODE_VAR) {
        switch (idGeneratorType) {
            case IdGenerator.Type.NATIVE:
                return "ID($variable)"
            case IdGenerator.Type.SNOWFLAKE:
                return "${variable}.${CypherBuilder.IDENTIFIER}"
            default:
                return "${variable}.${identity.name}"
        }
    }

    /**
     * Format a reference to the ID for cypher queries
     * @param variable The name of the variable for the id
     * @return The formatted id
     */
    String formatProperty(String variable, String property) {
        if(property == identity.name) {
            return formatId(variable)
        }
        else {
            return "${variable}.${property}"
        }
    }
    /**
     * @return Whether the ID is native
     */
    boolean isNativeId() {
        return nativeId
    }

    /**
     * recursively join all discriminators up the class hierarchy
     * @return
     */
    String getLabelsWithInheritance(domainInstance) {
        StringBuilder sb = new StringBuilder()
        appendRecursive(sb, domainInstance)
        return sb.toString()
    }
    /**
     * @return Returns only the statically defined labels
     */
    Collection<String> getLabels() {
        return this.staticLabels
    }

    /**
     * Get labels specific to the given instance
     *
     * @param domainInstance The domain instance
     * @return the abels
     */
    Collection<String> getLabels(Object domainInstance) {
        if(hasDynamicLabels) {
            Collection<String> labels = []
            for(obj in labelObjects) {
                String label = getLabelFor(obj, domainInstance)
                if(label) {
                    labels.add(label)
                }
            }
            return labels
        }
        else {
            return staticLabels
        }
    }

    /**
     * @return Return only the statically defined labels as a string usable by cypher, concatenated by ":"
     */
    String getLabelsAsString() {
        return ":${staticLabels.join(LABEL_SEPARATOR)}"
    }

    /**
     * return all labels as string usable for cypher, concatenated by ":"
     * @return
     */
    String getLabelsAsString(Object domainInstance) {
        if(hasDynamicLabels) {
            return ":${getLabels(domainInstance).join(LABEL_SEPARATOR)}"
        }
        else {
            return getLabelsAsString()
        }
    }

    /**
     * @return The variable name used in queries to query this entity
     */
    String getVariableName() {
        CypherBuilder.NODE_VAR
    }

    protected boolean establishLabels() {
        labelObjects = establishLabelObjects()
        boolean hasDynamicLabels = labelObjects.any() { it instanceof Closure }
        for (obj in labelObjects) {
            String label = getLabelFor(obj)
            if (label != null) {
                staticLabels.add(label)
            }
        }
        return hasDynamicLabels
    }

    protected Collection<Object> establishLabelObjects() {
        Object labels = mappedForm.getLabels();

        List objs = labels instanceof Object[] ? labels as List : [labels]

        // if labels consists solely of instance-dependent labels, add default label based on class name
        if (objs.every { (it instanceof Closure) && (it.maximumNumberOfParameters == 2) }) {
            objs << null // adding -> label defaults to discriminator
        }
        return objs
    }

    private String getLabelFor(Object obj, domainInstance = null) {
        switch (obj) {
            case null:
                return discriminator
            case CharSequence:
                return ((CharSequence)obj).toString()
            case Closure:
                Closure closure = (Closure)obj
                Object result = null
                switch (closure.maximumNumberOfParameters) {
                    case 1:
                        result = closure(this)
                        break
                    case 2:
                        result = domainInstance == null ? null : closure(this, domainInstance)
                        break
                    default:
                        throw new IllegalArgumentException("closure specified in labels is unsupported, it expects $closure.maximumNumberOfParameters parameters.")
                }
                return result?.toString()
            default:
                return obj.toString()
        }
    }

    private void appendRecursive(StringBuilder sb, domainInstance){
        sb.append(getLabelsAsString(domainInstance));

        def parentEntity = getParentEntity()
        if (parentEntity !=null && !GormMappingConfigurationStrategy.isAbstract(parentEntity)) {
            ((GraphPersistentEntity) parentEntity).appendRecursive(sb, domainInstance);
        }
    }

    boolean hasDynamicAssociations() {
        return this.hasDynamicAssociations
    }
}
