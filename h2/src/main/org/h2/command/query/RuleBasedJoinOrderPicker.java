package org.h2.command.query;

//I know this is bad practice, but I just didn't to add includes for what I needed
import java.util.*;
import org.h2.engine.SessionLocal;
import org.h2.table.TableFilter;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;

/**
 * Determines the best join order by following specific rules:
 * 1. Never introduce a cartesian product (only join tables with explicit ON conditions).
 * 2. Among valid join candidates, choose the one with the lowest row count.
 */
public class RuleBasedJoinOrderPicker {
    private final SessionLocal session;
    private final TableFilter[] filters;

    public RuleBasedJoinOrderPicker(SessionLocal session, TableFilter[] filters) {
        this.session = session;
        this.filters = filters;
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
            if (hasExplicitJoinCondition(ordered, tf)) { // Ensure no cartesian product
                long rowCount = tf.getTable().getRowCountApproximation(session);
                if (rowCount < minRowCount) {
                    minRowCount = rowCount;
                    bestFilter = tf;
                }
            }
        }
    
        return bestFilter;
    }

    private boolean hasExplicitJoinCondition(List<TableFilter> ordered, TableFilter candidate) {
        for (TableFilter orderedFilter : ordered) {
            Expression condition = orderedFilter.getFullCondition();
            if (condition != null && referencesBothTables(condition, orderedFilter, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean referencesBothTables(Expression condition, TableFilter table1, TableFilter table2) {
        Set<TableFilter> referencedTables = new HashSet<>();
        collectReferencedTables(condition, referencedTables);

        return referencedTables.contains(table1) && referencedTables.contains(table2);
    }

    private void collectReferencedTables(Expression expression, Set<TableFilter> referencedTables) {
        if (expression == null) return;

        // If the expression is a column reference, extract its table
        if (expression instanceof ExpressionColumn) {
            TableFilter filter = ((ExpressionColumn) expression).getTableFilter();
            if (filter != null) {
                referencedTables.add(filter);
            }
        }

        // Recursively check sub-expressions
        for (int i = 0; i < expression.getSubexpressionCount(); i++) {
            collectReferencedTables(expression.getSubexpression(i), referencedTables);
        }
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
