package org.h2.command.query;

import java.util.*;
import org.h2.engine.SessionLocal;
import org.h2.table.TableFilter;
import org.h2.expression.Expression;

public class RuleBasedJoinOrderPicker {
    private final SessionLocal session;
    private final TableFilter[] filters;
    private final Map<String, Set<String>> joinMap;

    public RuleBasedJoinOrderPicker(SessionLocal session, TableFilter[] filters) {
        this.session = session;
        this.filters = filters;
        this.joinMap = new HashMap<>();
        buildJoinMap();
    }

    // Step 1: Build the map of join relationships
    private void buildJoinMap() {
        for (TableFilter tf : filters) {
            String tableName = tf.getTable().getName();
            joinMap.putIfAbsent(tableName, new HashSet<>());
        }

        for (TableFilter tf : filters) {
            Expression condition = tf.getFullCondition();
            if (condition != null) {
                collectJoinConditions(condition);
            }
        }
    }

    // Step 2: Collect the tables involved in join conditions and update the join map
    private void collectJoinConditions(Expression expression) {
        if (expression == null) return;

        if (expression.getSubexpressionCount() == 2 &&
            expression.getSubexpression(0).getSubexpressionCount() == 0 &&
            expression.getSubexpression(1).getSubexpressionCount() == 0) {

            String tableName1 = expression.getSubexpression(0).getTableName();
            String tableName2 = expression.getSubexpression(1).getTableName();
            
            // Add each table to the other table's list in the map
            joinMap.get(tableName1).add(tableName2);
            joinMap.get(tableName2).add(tableName1);
        }

        // Recursively check sub-expressions
        for (int i = 0; i < expression.getSubexpressionCount(); i++) {
            collectJoinConditions(expression.getSubexpression(i));
        }
    }

    private TableFilter findSmallestTable(Set<TableFilter> remaining) {
        TableFilter smallestTable = null;
        long minRowCount = Long.MAX_VALUE;

        for (TableFilter tf : remaining) {
            long rowCount = tf.getTable().getRowCountApproximation(session);
            if (rowCount < minRowCount) {
                minRowCount = rowCount;
                smallestTable = tf;
            }
        }

        return smallestTable;
    }

    private TableFilter findNextBest(List<TableFilter> ordered, Set<TableFilter> remaining) {
        TableFilter bestFilter = null;
        long minRowCount = Long.MAX_VALUE;

        for (TableFilter tf : remaining) {
            if (canJoinWithOrderedTables(ordered, tf)) { // Check if the table can join with any in the ordered list
                long rowCount = tf.getTable().getRowCountApproximation(session);
                if (rowCount < minRowCount) {
                    minRowCount = rowCount;
                    bestFilter = tf;
                }
            }
        }

        return bestFilter;
    }

    // Step 3: Check if the current table can be joined with any table already in the ordered list
    private boolean canJoinWithOrderedTables(List<TableFilter> ordered, TableFilter candidate) {
        String candidateTable = candidate.getTable().getName();
        
        for (TableFilter orderedFilter : ordered) {
            String orderedTable = orderedFilter.getTable().getName();
            // Check if there is a join condition between the candidate table and the ordered table
            if (joinMap.get(orderedTable).contains(candidateTable)) {
                return true;
            }
        }
        return false;
    }

    public TableFilter[] bestOrder() {
        List<TableFilter> orderedFilters = new ArrayList<>();
        Set<TableFilter> remaining = new HashSet<>(Arrays.asList(filters));

        // Step 1: Find the smallest table to start
        TableFilter smallest = findSmallestTable(remaining);
        if (smallest != null) {
            orderedFilters.add(smallest);
            remaining.remove(smallest);
        }

        // Step 2: Iteratively add the smallest valid joinable table
        while (!remaining.isEmpty()) {
            TableFilter next = findNextBest(orderedFilters, remaining);
            if (next != null) {
                orderedFilters.add(next);
                remaining.remove(next);
            } else {
                // If no valid joinable table is found, stop to prevent a cartesian product
                break;
            }
        }

        return orderedFilters.toArray(new TableFilter[0]);
    }
}
