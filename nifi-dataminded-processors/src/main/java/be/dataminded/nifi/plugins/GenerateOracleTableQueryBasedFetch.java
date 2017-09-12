/*
 * Copyright 2017 Data Minded
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package be.dataminded.nifi.plugins;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;


@Tags({"database", "sql", "table", "dataminded"})
@CapabilityDescription("Generate queries to extract all data from database tables")
@WritesAttributes({
        @WritesAttribute(attribute = "tenant.name", description = "Hint for which tenant this data is ingested"),
        @WritesAttribute(attribute = "source.name", description = "Hint for which source this data is ingested"),
        @WritesAttribute(attribute = "schema.name", description = "Hint for which schema this data is ingested"),
        @WritesAttribute(attribute = "table.name", description = "The table name for which the queries are generated"),
        @WritesAttribute(attribute = "generateoracletablefetch.total.row.count", description = "the count of the complete table"),
        @WritesAttribute(attribute = "fragment.identifier", description = "All split FlowFiles produced from this query will have the same randomly generated UUID added for this attribute"),
        @WritesAttribute(attribute = "fragment.index", description = "A one-up number that indicates the ordering of the split FlowFiles that were created from a single parent FlowFile"),
        @WritesAttribute(attribute = "fragment.count", description = "The number of split FlowFiles generated from the parent FlowFile"),
        @WritesAttribute(attribute = "fragment.sql", description = "The query for the segement"),
        @WritesAttribute(attribute = "segment.original.filename", description = "The filename for all flowFiles (defragment expects this)")
})
public class GenerateOracleTableQueryBasedFetch extends AbstractProcessor {

    private class IncrementalState {
        private String maxValueColumnCurrentState;
        private String maxValueColumnNewState;

        void readCurrentState(String filePath,
                              String defaultMaxValueColumnStartValue) throws IOException {
            // set the default value from Nifi properties
            this.maxValueColumnCurrentState = defaultMaxValueColumnStartValue;

            // if a state file is set and exists, we load it from there
            if (!StringUtils.isEmpty(filePath)) {
                File f = new File(filePath);
                if(f.exists() && !f.isDirectory()) {
                    Properties properties = new Properties();
                    properties.load(new FileInputStream(filePath));
                    this.maxValueColumnCurrentState = properties.getProperty("maxValueColumnCurrentState");
                }
            }
        }

        void writeNewState(String filePath) throws IOException {
            if(!StringUtils.isEmpty(filePath)) {
                // writes the new state to the file
                Properties properties = new Properties();
                if (!StringUtils.isEmpty(maxValueColumnNewState)) {
                    properties.setProperty("maxValueColumnCurrentState", this.maxValueColumnNewState);
                }
                properties.store(new FileWriter(filePath), "Writing properties file");
            }
        }

        boolean hasChanged() {
            return !Objects.equals(this.maxValueColumnNewState, this.maxValueColumnCurrentState);
        }

        boolean exists() {
            return !StringUtils.isEmpty(maxValueColumnCurrentState);
        }

        String getMaxValueColumnCurrentState() {
            return maxValueColumnCurrentState;
        }

        void setMaxValueColumnNewState(String maxValueColumnNewState) {
            this.maxValueColumnNewState = maxValueColumnNewState;
        }
    }

    private static final String MAX_VALUE_COLUMN_TYPE_NONE = "None";
    private static final String MAX_VALUE_COLUMN_TYPE_INT = "Integer";
    private static final String MAX_VALUE_COLUMN_TYPE_DATE = "Date";
    private static final String MAX_VALUE_COLUMN_TYPE_TIMESTAMP = "Timestamp";

    private static final String COUNT_SPLIT_COLUMN_NAME = "CountSplit";
    private static final String MIN_SPLIT_COLUMN_NAME = "MinSplit";
    private static final String MAX_SPLIT_COLUMN_NAME = "MaxSplit";

    private static final Relationship REL_SUCCESS;

    private static final PropertyDescriptor DBCP_SERVICE;
    private static final PropertyDescriptor TABLE_NAME;
    private static final PropertyDescriptor COLUMN_NAMES;
    private static final PropertyDescriptor MAX_VALUE_QUERY;
    private static final PropertyDescriptor MAX_VALUE_COLUMN;
    private static final PropertyDescriptor MAX_VALUE_COLUMN_TYPE;
    private static final PropertyDescriptor MAX_VALUE_COLUMN_TYPE_OPTION;
    private static final PropertyDescriptor MAX_VALUE_COLUMN_START_VALUE;
    private static final PropertyDescriptor QUERY_TIMEOUT;
    private static final PropertyDescriptor NUMBER_OF_RECORDS_PER_PARTITIONS;
    private static final PropertyDescriptor SPLIT_COLUMN;
    private static final PropertyDescriptor STATE_FILE;

    private static final PropertyDescriptor TENANT;
    private static final PropertyDescriptor SOURCE;
    private static final PropertyDescriptor SCHEMA;

    private static final PropertyDescriptor OPTION_TO_NUMBER;
    private static final PropertyDescriptor CONDITION;


    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext validationContext) {
        Set<ValidationResult> results = new HashSet<>();

        final String stateFile = validationContext.getProperty(STATE_FILE).getValue();

        // Ensure that if max value column is set, also the state file and query is set
        String maxValueColumnName = validationContext.getProperty(MAX_VALUE_COLUMN).getValue();
        String maxValueQuery = validationContext.getProperty(MAX_VALUE_QUERY).getValue();

        if (StringUtils.isEmpty(maxValueColumnName) != StringUtils.isEmpty(maxValueQuery)) {
            results.add(new ValidationResult.Builder().valid(false).explanation(
                    "Maximum-value Column Name and Maximum-value Query must both be set.").build());
        }

        if (!StringUtils.isEmpty(maxValueColumnName) && StringUtils.isEmpty(stateFile)) {
            results.add(new ValidationResult.Builder().valid(false).explanation(
                    "Maximum-value Column Name can not be set without setting the state file.").build());
        }

        return results;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        final ComponentLog logger = getLogger();

        final DBCPService dbcpService = context.getProperty(DBCP_SERVICE).asControllerService(DBCPService.class);
        final String tableName = context.getProperty(TABLE_NAME).getValue();
        final String schema = context.getProperty(SCHEMA).getValue();
        final String columnNames = context.getProperty(COLUMN_NAMES).getValue();
        final String maxValueQuery = context.getProperty(MAX_VALUE_QUERY).getValue();
        final String maxValueColumnName = context.getProperty(MAX_VALUE_COLUMN).getValue();
        final String maxValueColumnType = context.getProperty(MAX_VALUE_COLUMN_TYPE).getValue();
        final String maxValueColumnTypeOption = context.getProperty(MAX_VALUE_COLUMN_TYPE_OPTION).getValue();
        final String maxValueColumnStartValue = context.getProperty(MAX_VALUE_COLUMN_START_VALUE).getValue();
        final String splitColumnName = context.getProperty(SPLIT_COLUMN).getValue();
        final String stateFile = context.getProperty(STATE_FILE).getValue();
        final int numberOfRecordsPerPartition = Integer.parseInt(context.getProperty(NUMBER_OF_RECORDS_PER_PARTITIONS).getValue());
        final Integer queryTimeout = context.getProperty(QUERY_TIMEOUT).asTimePeriod(TimeUnit.SECONDS).intValue();
        final boolean optionalToNumber = context.getProperty(OPTION_TO_NUMBER).asBoolean();
        final String condition = context.getProperty(CONDITION).getValue();

        try {
            boolean isIncremental = !StringUtils.isEmpty(maxValueQuery);
            IncrementalState state = null;

            if (isIncremental) {
                state = new IncrementalState();

                // Get the current recorded incremental state from state storage
                try {
                    state.readCurrentState(stateFile, maxValueColumnStartValue);
                } catch (IOException e) {
                    logger.error("Failed to retrieve observed maximum values from the state . Will not perform "
                            + "query until this is accomplished.", e);
                    context.yield();
                    return;
                }

                // Get the new incremental state from the max value query
                try (final Connection con = dbcpService.getConnection();
                     final Statement statement = con.createStatement()) {
                    statement.setQueryTimeout(queryTimeout);
                    logger.debug("Executing {}", new Object[]{maxValueQuery});

                    ResultSet resultSet = statement.executeQuery(maxValueQuery);
                    if (resultSet.next()) {
                        String newState = resultSet.getString(1);
                        state.setMaxValueColumnNewState(newState);
                    } else {
                        logger.error(
                                "Something is very wrong here, one row (even if count is zero) should have been returned: {}",
                                new Object[]{maxValueQuery});
                        throw new SQLException("No rows returned from metadata query: " + maxValueQuery);
                    }
                } catch (SQLException e) {
                    logger.error("Unable to execute SQL select query {} due to {}", new Object[]{maxValueQuery, e});
                    throw new ProcessException(e);
                }
            }

            // if we are not incremental or state has changed
            if(!isIncremental || state.hasChanged()) {

                // Building the meta query
                List<String> metaWhereClauses = new ArrayList<>();
                List<String> metaSelectClauses = new ArrayList<>();

                metaSelectClauses.add(String.format("COUNT(%s) AS %s", splitColumnName, COUNT_SPLIT_COLUMN_NAME));
                if (optionalToNumber) {
                    metaSelectClauses.add(String.format("MIN(TO_NUMBER(%s)) AS %s", splitColumnName, MIN_SPLIT_COLUMN_NAME));
                    metaSelectClauses.add(String.format("MAX(TO_NUMBER(%s)) AS %s", splitColumnName, MAX_SPLIT_COLUMN_NAME));
                } else {
                    metaSelectClauses.add(String.format("MIN(%s) AS %s", splitColumnName, MIN_SPLIT_COLUMN_NAME));
                    metaSelectClauses.add(String.format("MAX(%s) AS %s", splitColumnName, MAX_SPLIT_COLUMN_NAME));
                }

                // Add incremental condition
                String maxValueWhereClause = "";
                if (isIncremental && state.exists()) {
                    maxValueWhereClause = getMaxValueWhereClause(maxValueColumnName,
                            maxValueColumnType,
                            maxValueColumnTypeOption,
                            state.getMaxValueColumnCurrentState());
                    metaWhereClauses.add(maxValueWhereClause);
                }

                // Add additional condition
                if (!StringUtils.isEmpty(condition)) {
                    metaWhereClauses.add(condition);
                }

                // Composing the query
                String selectQuery = String.format("SELECT %s FROM %s.%s WHERE %s", String.join(", ", metaSelectClauses),
                        schema, tableName, String.join(" AND ", metaWhereClauses));
                if (metaWhereClauses.isEmpty()) {
                     selectQuery = String.format("SELECT %s FROM %s.%s", String.join(", ", metaSelectClauses),
                            schema, tableName);
                }

                // Fetch metadata from the database
                long low, high, numberOfRecords;
                try (final Connection con = dbcpService.getConnection();
                     final Statement statement = con.createStatement()) {
                    statement.setQueryTimeout(queryTimeout);
                    logger.debug("Executing {}", new Object[]{selectQuery});

                    ResultSet resultSet = statement.executeQuery(selectQuery);
                    if (resultSet.next()) {
                        low = resultSet.getLong(MIN_SPLIT_COLUMN_NAME);
                        high = resultSet.getLong(MAX_SPLIT_COLUMN_NAME);
                        numberOfRecords = resultSet.getLong(COUNT_SPLIT_COLUMN_NAME);
                    } else {
                        logger.error(
                                "Something is very wrong here, one row (even if count is zero) should have been returned: {}",
                                new Object[]{selectQuery});
                        throw new SQLException("No rows returned from metadata query: " + selectQuery);
                    }
                } catch (SQLException e) {
                    logger.error("Unable to execute SQL select query {} due to {}", new Object[]{selectQuery, e});
                    throw new ProcessException(e);
                }

                // Generate queries
                long chunks = (numberOfRecords + numberOfRecordsPerPartition - 1) / numberOfRecordsPerPartition;
                long chunkSize = (high - low) / Math.max(chunks, 1);
                final String fragmentIdentifier = UUID.randomUUID().toString();
                for (int i = 0; i < chunks; i++) {
                    long min = (low + i * chunkSize);
                    long max = (i == chunks - 1) ? high : Math.min((i + 1) * chunkSize - 1 + low, high);
                    List<String> queryWhereClauses = new ArrayList<>();
                    queryWhereClauses.add(String.format("%s BETWEEN %s AND %s", splitColumnName, min, max));

                    if (!StringUtils.isEmpty(maxValueWhereClause)) {
                        queryWhereClauses.add(maxValueWhereClause);
                    }

                    if (!StringUtils.isEmpty(condition)) {
                        queryWhereClauses.add(condition);
                    }
                    String query = String.format("SELECT %s FROM %s.%s WHERE %s",
                            columnNames,
                            schema,
                            tableName,
                            String.join(" AND ", queryWhereClauses));
                    FlowFile sqlFlowFile = session.create();
                    sqlFlowFile = session.write(sqlFlowFile, out -> out.write(query.getBytes()));
                    sqlFlowFile = session.putAttribute(sqlFlowFile, "table.name", sanitizeAttribute(tableName));
                    sqlFlowFile = session.putAttribute(sqlFlowFile, "generateoracletablefetch.total.row.count", String.valueOf(numberOfRecords));
                    sqlFlowFile = session.putAttribute(sqlFlowFile, "fragment.identifier", fragmentIdentifier);
                    sqlFlowFile = session.putAttribute(sqlFlowFile, "fragment.index", Integer.toString(i));
                    sqlFlowFile = session.putAttribute(sqlFlowFile, "segment.original.filename", fragmentIdentifier);
                    sqlFlowFile = session.putAttribute(sqlFlowFile, "fragment.count", Long.toString(chunks));
                    sqlFlowFile = session.putAttribute(sqlFlowFile, "fragment.sql", query);

                    String tenant = context.getProperty(TENANT).getValue();
                    String source = context.getProperty(SOURCE).getValue();

                    if (tenant != null) {
                        sqlFlowFile = session.putAttribute(sqlFlowFile, "tenant.name", sanitizeAttribute(tenant));
                    }

                    if (source != null) {
                        sqlFlowFile = session.putAttribute(sqlFlowFile, "source.name", sanitizeAttribute(source));
                    }

                    if (schema != null) {
                        sqlFlowFile = session.putAttribute(sqlFlowFile, "schema.name", sanitizeAttribute(schema));
                    }

                    session.transfer(sqlFlowFile, REL_SUCCESS);
                }
            }
            session.commit();

            if(isIncremental) {
                try {
                    // Update the state
                    state.writeNewState(stateFile);
                } catch (IOException ioe) {
                    logger.error("{} failed to update State, observed maximum value will not be recorded. "
                                    + "Also, any generated SQL statements may be duplicated.",
                            new Object[]{this, ioe});
                }
            }

        } catch (final ProcessException pe) {
            // Log the cause of the ProcessException if it is available
            Throwable t = (pe.getCause() == null ? pe : pe.getCause());
            logger.error("Error during processing: {}", new Object[]{t.getMessage()}, t);
            session.rollback();
            context.yield();
        }
    }

    private static String getMaxValueWhereClause(String maxValueColumnName, String maxValueColumnType,
                                                 String maxValueColumnTypeOption, String maxValue)  {
        String condition = "";
        switch (maxValueColumnType) {
            case MAX_VALUE_COLUMN_TYPE_NONE:
            case MAX_VALUE_COLUMN_TYPE_INT:
                condition = String.format("%s >= %s", maxValueColumnName, maxValue);
                break;
            case MAX_VALUE_COLUMN_TYPE_DATE:
                condition = String.format("%s >= TO_DATE('%s', '%s')", maxValueColumnName, maxValue,
                        maxValueColumnTypeOption);
                break;
            case MAX_VALUE_COLUMN_TYPE_TIMESTAMP:
                condition = String.format("%s >= TO_TIMESTAMP('%s', '%s')", maxValueColumnName, maxValue,
                        maxValueColumnTypeOption);
                break;
        }
        return condition;
    }

    private static String getStateKey(String prefix, String columnName) {

        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix.toLowerCase());
            sb.append("@!@");
        }
        if (columnName != null) {
            sb.append(columnName.toLowerCase());
        }
        return sb.toString();
    }

    private String sanitizeAttribute(String attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.toLowerCase().replaceAll("_", "-");
    }


    @Override
    public Set<Relationship> getRelationships() {
        return ImmutableSet.of(REL_SUCCESS);
    }


    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return ImmutableList.of(DBCP_SERVICE,
                TABLE_NAME,
                COLUMN_NAMES,
                MAX_VALUE_QUERY,
                MAX_VALUE_COLUMN,
                MAX_VALUE_COLUMN_TYPE,
                MAX_VALUE_COLUMN_TYPE_OPTION,
                MAX_VALUE_COLUMN_START_VALUE,
                QUERY_TIMEOUT,
                NUMBER_OF_RECORDS_PER_PARTITIONS,
                SPLIT_COLUMN,
                STATE_FILE,
                TENANT,
                SOURCE,
                SCHEMA,
                OPTION_TO_NUMBER,
                CONDITION);
    }

    static {


        REL_SUCCESS = new Relationship.Builder()
                .name("success")
                .description("Successfully created FlowFile from SQL query result set.")
                .build();

        DBCP_SERVICE = new PropertyDescriptor.Builder()
                .name("Database Connection Pooling Service")
                .description("The Controller Service that is used to obtain a connection to the database.")
                .required(true)
                .identifiesControllerService(DBCPService.class)
                .build();

        TABLE_NAME = new PropertyDescriptor.Builder()
                .name("Table Name")
                .description("The name of the database table to be queried.")
                .required(true)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR).build();

        COLUMN_NAMES = new PropertyDescriptor.Builder()
                .name("Columns to Return")
                .description(
                        "A comma-separated list of column names to be used in the query. If your database requires special treatment of the names (quoting, e.g.), each name should include such treatment. If no column names are supplied, all columns in the specified table will be returned.")
                .required(false)
                .defaultValue("*")
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build();

        MAX_VALUE_QUERY = new PropertyDescriptor.Builder()
                .name("Maximum-value query")
                .description("Query that returns the max value as a string")
                .required(false)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build();

        MAX_VALUE_COLUMN = new PropertyDescriptor.Builder()
                .name("Maximum-value Column Name")
                .description("A column name. The processor will keep track of the maximum value "
                        + "for the column that has been returned since the processor started running. This processor "
                        + "can be used to retrieve only those rows that have been added/updated since the last retrieval. Note that some "
                        + "JDBC types such as bit/boolean are not conducive to maintaining maximum value, so columns of these "
                        + "types should not be listed in this property, and will result in error(s) during processing. NOTE: It is important "
                        + "to use consistent max-value column names for a given table for incremental fetch to work properly.")
                .required(false)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build();

        MAX_VALUE_COLUMN_TYPE = new PropertyDescriptor.Builder()
                .name("Maximum-value Column Type")
                .description("The type of the max value column so we can build the correct queries")
                .required(false)
                .allowableValues(MAX_VALUE_COLUMN_TYPE_NONE, MAX_VALUE_COLUMN_TYPE_INT, MAX_VALUE_COLUMN_TYPE_DATE,
                        MAX_VALUE_COLUMN_TYPE_TIMESTAMP)
                .defaultValue(MAX_VALUE_COLUMN_TYPE_NONE)
                .build();

        MAX_VALUE_COLUMN_TYPE_OPTION = new PropertyDescriptor.Builder()
                .name("Maximum-value Column Type option")
                .description("Some types, like Date and Timstamp, require additional options to work as expected")
                .required(false)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build();

        MAX_VALUE_COLUMN_START_VALUE = new PropertyDescriptor.Builder()
                .name("Maximum-value Column start value")
                .description("The initial value for Maximum-value Column to start from.")
                .required(false)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build();

        QUERY_TIMEOUT = new PropertyDescriptor.Builder()
                .name("Max Wait Time")
                .description(
                        "The maximum amount of time allowed for a running SQL select query , zero means there is no limit. Max time less than 1 second will be equal to zero.")
                .defaultValue("0 seconds")
                .required(true)
                .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
                .build();

        NUMBER_OF_RECORDS_PER_PARTITIONS = new PropertyDescriptor.Builder()
                .name("number-of-records-per-partition")
                .displayName("Number of records per partition")
                .defaultValue("10000")
                .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
                .build();

        SPLIT_COLUMN = new PropertyDescriptor.Builder()
                .name("split-column")
                .displayName("Split column")
                .required(true)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
                .build();

        STATE_FILE = new PropertyDescriptor.Builder()
                .name("state-file")
                .displayName("State file")
                .required(false)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
                .build();

        TENANT = new PropertyDescriptor.Builder()
                .name("tenant")
                .displayName("Tenant")
                .required(false)
                .defaultValue(null)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .description("Hint for which tenant this data is ingested")
                .build();

        SOURCE = new PropertyDescriptor.Builder()
                .name("source")
                .displayName("Source")
                .required(false)
                .defaultValue(null)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .description("Hint for which source this data is ingested")
                .build();

        SCHEMA = new PropertyDescriptor.Builder()
                .name("schema")
                .displayName("Schema")
                .defaultValue(null)
                .required(true)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .description("Hint for which schema this data is ingested")
                .build();

        OPTION_TO_NUMBER = new PropertyDescriptor.Builder()
                .name("optionalToNumber")
                .displayName("Optional to number")
                .defaultValue("false")
                .required(false)
                .allowableValues("true", "false")
                .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
                .description("option if the split column has to be cast to a number")
                .build();

        CONDITION = new PropertyDescriptor.Builder()
                .name("condition")
                .displayName("Condition")
                .defaultValue(null)
                .required(false)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .description("Additional WHERE clause for the query")
                .build();
    }
}