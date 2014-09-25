/*
 * Licensed to STRATIO (C) under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership. The STRATIO
 * (C) licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.stratio.meta2.core.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.stratio.meta.common.connector.Operations;
import com.stratio.meta.common.executionplan.ExecutionWorkflow;
import com.stratio.meta.common.logicalplan.Filter;
import com.stratio.meta.common.logicalplan.Join;
import com.stratio.meta.common.logicalplan.Limit;
import com.stratio.meta.common.logicalplan.LogicalStep;
import com.stratio.meta.common.logicalplan.LogicalWorkflow;
import com.stratio.meta.common.logicalplan.Project;
import com.stratio.meta.common.logicalplan.Select;
import com.stratio.meta.common.logicalplan.UnionStep;
import com.stratio.meta.common.statements.structures.relationships.Operator;
import com.stratio.meta.common.statements.structures.relationships.Relation;
import com.stratio.meta.core.structures.InnerJoin;
import com.stratio.meta2.common.data.ColumnName;
import com.stratio.meta2.common.data.Status;
import com.stratio.meta2.common.data.TableName;
import com.stratio.meta2.common.metadata.ColumnType;
import com.stratio.meta2.common.metadata.ConnectorMetadata;
import com.stratio.meta2.common.metadata.TableMetadata;
import com.stratio.meta2.common.statements.structures.selectors.ColumnSelector;
import com.stratio.meta2.common.statements.structures.selectors.Selector;
import com.stratio.meta2.common.statements.structures.selectors.SelectorType;
import com.stratio.meta2.core.metadata.MetadataManager;
import com.stratio.meta2.core.query.MetadataValidatedQuery;
import com.stratio.meta2.core.query.SelectPlannedQuery;
import com.stratio.meta2.core.query.SelectValidatedQuery;
import com.stratio.meta2.core.query.StorageValidatedQuery;
import com.stratio.meta2.core.query.ValidatedQuery;
import com.stratio.meta2.core.statements.SelectStatement;

/**
 * Class in charge of defining the set of {@link com.stratio.meta.common.logicalplan.LogicalStep}
 * required to execute a statement. This set of steps are ordered as a workflow on a {@link
 * com.stratio.meta.common.logicalplan.LogicalWorkflow} structure. Notice that the LogicalWorkflow
 * may contain several initial steps, but it will always finish in a single operation.
 */
public class Planner {

    /**
     * Class logger.
     */
    private static final Logger LOG = Logger.getLogger(Planner.class);

    /**
     * Create a PlannedQuery with the {@link com.stratio.meta.common.logicalplan.LogicalWorkflow}
     * required to execute the user statement. This method is intended to be used only with Select
     * statements as any other can be directly executed.
     *
     * @param query A {@link com.stratio.meta2.core.query.NormalizedQuery}.
     * @return A {@link com.stratio.meta2.core.query.PlannedQuery}.
     */
    public SelectPlannedQuery planQuery(ValidatedQuery query) {
        //Build the workflow.
        LogicalWorkflow workflow = buildWorkflow((SelectValidatedQuery) query);


        //TODO set queryID for Execution query.getQueryId()

        //Plan the workflow execution into different connectors.
        ExecutionWorkflow executionWorkflow = null;

        //Return the planned query.

        SelectPlannedQuery pq = new SelectPlannedQuery((SelectValidatedQuery) query, executionWorkflow);

        return pq;
    }

    protected LogicalWorkflow buildWorkflow(ValidatedQuery query) {
        LogicalWorkflow result = null;
        if (query instanceof SelectValidatedQuery) {
            result = buildWorkflow((SelectValidatedQuery) query);
        } else if (query instanceof StorageValidatedQuery) {
            result = buildWorkflow((StorageValidatedQuery) query);
        } else if (query instanceof MetadataValidatedQuery) {
            result = buildWorkflow((MetadataValidatedQuery) query);
        }
        return result;
    }

    protected ExecutionWorkflow buildExecutionWorkflow(LogicalWorkflow workflow) {

        List<TableName> tables = new ArrayList<>(workflow.getInitialSteps().size());
        for(LogicalStep ls : workflow.getInitialSteps()){
            tables.add(Project.class.cast(ls).getTableName());
        }

        //Get the list of connector attached to the clusters that contain the required tables.
        Map<TableName, List<ConnectorMetadata>> initialConnectors = MetadataManager.MANAGER.getAttachedConnectors(
                Status.ONLINE, tables);

        //Refine the list of available connectors and determine which connector to be used.

        return null;
    }

    /**
     * Build a workflow with the {@link com.stratio.meta.common.logicalplan.LogicalStep} required to
     * execute a query. This method does not determine which connector will execute which part of the
     * workflow.
     *
     * @param query The query to be planned.
     * @return A Logical workflow.
     */
    protected LogicalWorkflow buildWorkflow(SelectValidatedQuery query) {
        Map<String, TableMetadata> tableMetadataMap = new HashMap<>();
        for (TableMetadata tm : query.getTableMetadata()) {
            tableMetadataMap.put(tm.getName().getQualifiedName(), tm);
        }
        //Define the list of projects
        Map<String, LogicalStep> processed = getProjects(query, tableMetadataMap);
        addProjectedColumns(processed, query);

        //TODO determine which is the correct target table if the order fails.
        String selectTable = query.getTables().get(0).getQualifiedName();

        //Add filters
        if (query.getRelationships() != null) {
            processed = addFilter(processed, tableMetadataMap, query);
        }

        //Add join
        if (query.getJoin() != null) {
            processed = addJoin(processed, selectTable, query);
        }

        //Prepare the result.
        List<LogicalStep> initialSteps = new ArrayList<>();
        LogicalStep initial = null;
        for (LogicalStep ls : processed.values()) {
            if (!UnionStep.class.isInstance(ls)) {
                initial = ls;
                //Go to the first element of the workflow
                while (initial.getFirstPrevious() != null) {
                    initial = initial.getFirstPrevious();
                }
                if (Project.class.isInstance(initial)) {
                    initialSteps.add(initial);
                }
            }
        }

        //Find the last element
        LogicalStep last = initial;
        while (last.getNextStep() != null) {
            last = last.getNextStep();
        }

        //Add LIMIT clause
        SelectStatement ss = SelectStatement.class.cast(query.getStatement());
        if (ss.isLimitInc()) {
            Limit l = new Limit(Operations.SELECT_LIMIT, ss.getLimit());
            last.setNextStep(l);
            l.setPrevious(last);
            last = l;
        }

        //Add SELECT operator
        Select finalSelect = generateSelect(ss, tableMetadataMap);
        last.setNextStep(finalSelect);
        finalSelect.setPrevious(last);

        LogicalWorkflow workflow = new LogicalWorkflow(initialSteps);
        workflow.setLastStep(finalSelect);

        return workflow;
    }

    protected LogicalWorkflow buildWorkflow(StorageValidatedQuery query) {
        throw new UnsupportedOperationException();
    }

    protected LogicalWorkflow buildWorkflow(MetadataValidatedQuery query) {
        throw new UnsupportedOperationException();
    }

    /**
     * Add the columns that need to be retrieved to the initial steps map.
     *
     * @param projectSteps The map associating table names to Project steps.
     * @param query        The query to be planned.
     */
    private void addProjectedColumns(Map<String, LogicalStep> projectSteps, SelectValidatedQuery query) {
        for (ColumnName cn : query.getColumns()) {
            Project.class.cast(projectSteps.get(cn.getTableName().getQualifiedName())).addColumn(cn);
        }
    }

    /**
     * Get the filter operation depending on the type of column and the selector.
     *
     * @param tableName The table metadata.
     * @param selector  The relationship selector.
     * @param operator  The relationship operator.
     * @return An {@link com.stratio.meta.common.connector.Operations} object.
     */
    protected Operations getFilterOperation(final TableMetadata tableName,
            final Selector selector,
            final Operator operator) {
        StringBuilder sb = new StringBuilder("FILTER_");
        if (SelectorType.FUNCTION.equals(selector.getType())) {
            sb.append("FUNCTION_");
        } else {
            ColumnSelector cs = ColumnSelector.class.cast(selector);
            if (tableName.isPK(cs.getName())) {
                sb.append("PK_");
            } else if (tableName.isIndexed(cs.getName())) {
                sb.append("INDEXED_");
            } else {
                sb.append("NON_INDEXED_");
            }
        }
        sb.append(operator.name());
        return Operations.valueOf(sb.toString());
    }

    /**
     * Add Filter operations after the Project. The Filter operations to be applied are associated
     * with the where clause found.
     *
     * @param lastSteps        The map associating table names to Project steps.
     * @param tableMetadataMap A map with the table metadata indexed by table name.
     * @param query            The query to be planned.
     */
    private Map<String, LogicalStep> addFilter(Map<String, LogicalStep> lastSteps,
            Map<String, TableMetadata> tableMetadataMap,
            SelectValidatedQuery query) {
        LogicalStep previous = null;
        TableMetadata tm = null;
        Selector s = null;
        for (Relation r : query.getRelationships()) {
            s = r.getLeftTerm();
            //TODO Support left-side functions that contain columns of several tables.
            tm = tableMetadataMap.get(s.getSelectorTablesAsString());
            if (tm != null) {
                Operations op = getFilterOperation(tm, s, r.getOperator());
                Filter f = new Filter(op, r);
                previous = lastSteps.get(s.getSelectorTablesAsString());
                previous.setNextStep(f);
                f.setPrevious(previous);
                lastSteps.put(s.getSelectorTablesAsString(), f);
            } else {
                LOG.error("Cannot determine Filter for relation " + r.toString() + " on table " + s
                        .getSelectorTablesAsString());
            }

        }
        return lastSteps;
    }

    /**
     * Add the join logical steps.
     *
     * @param stepMap     The map of last steps after adding filters.
     * @param targetTable The target table of the join.
     * @param query       The query.
     * @return The resulting map of logical steps.
     */
    private Map<String, LogicalStep> addJoin(Map<String, LogicalStep> stepMap, String targetTable,
            SelectValidatedQuery query) {
        InnerJoin queryJoin = query.getJoin();
        String id = new StringBuilder(targetTable).append("$").append(queryJoin.getTablename().getQualifiedName())
                .toString();
        Join j = new Join(Operations.SELECT_INNER_JOIN, id);
        j.addSourceIdentifier(targetTable);
        j.addSourceIdentifier(queryJoin.getTablename().getQualifiedName());
        j.addJoinRelations(queryJoin.getRelations());
        StringBuilder sb = new StringBuilder(targetTable)
                .append("$").append(queryJoin.getTablename().getQualifiedName());
        //Attach to input tables path
        LogicalStep t1 = stepMap.get(targetTable);
        LogicalStep t2 = stepMap.get(queryJoin.getTablename().getQualifiedName());
        t1.setNextStep(j);
        t2.setNextStep(j);
        j.addPreviousSteps(t1, t2);
        j.addSourceIdentifier(targetTable);
        j.addSourceIdentifier(queryJoin.getTablename().getQualifiedName());
        stepMap.put(sb.toString(), j);
        return stepMap;
    }

    /**
     * Get a Map associating fully qualified table names with their Project logical step.
     *
     * @param query            The query to be planned.
     * @param tableMetadataMap Map of table metadata.
     * @return A map with the projections.
     */
    protected Map<String, LogicalStep> getProjects(SelectValidatedQuery query,
            Map<String, TableMetadata> tableMetadataMap) {
        Map<String, LogicalStep> projects = new HashMap<>();
        for (TableName tn : query.getTables()) {
            Project p = new Project(Operations.PROJECT, tn,
                    tableMetadataMap.get(tn.getQualifiedName()).getClusterRef());
            projects.put(tn.getQualifiedName(), p);
        }
        return projects;
    }

    protected Select generateSelect(SelectStatement selectStatement, Map<String, TableMetadata> tableMetadataMap) {
        Map<String, String> aliasMap = new HashMap<>();
        Map<String, ColumnType> typeMap = new HashMap<>();
        for (Selector s : selectStatement.getSelectExpression().getSelectorList()) {
            if (s.getAlias() != null) {
                aliasMap.put(s.toString(), s.getAlias());

                typeMap.put(s.toString(),
                        tableMetadataMap.get(s.getSelectorTablesAsString()).getColumns()
                                .get(ColumnSelector.class.cast(s).getName()).getColumnType());
            } else {
                aliasMap.put(s.toString(), s.toString());
            }
        }
        Select result = new Select(Operations.SELECT_OPERATOR, aliasMap, typeMap);
        return result;
    }

}
