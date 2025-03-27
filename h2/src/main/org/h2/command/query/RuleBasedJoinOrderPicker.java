package org.h2.command.query;
 
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


import org.h2.engine.SessionLocal;
import org.h2.table.TableFilter;
import org.h2.expression.Expression;
 
/**
 * Determines the best join order by following rules rather than considering every possible permutation.
 */
public class RuleBasedJoinOrderPicker {
    final SessionLocal session;
    final TableFilter[] filters;
 
    public RuleBasedJoinOrderPicker(SessionLocal session, TableFilter[] filters) {
    this.session = session;
    this.filters = filters;
    }
    
    public TableFilter[] bestOrder() {
        
        List<TableFilter> orderedFilters = new ArrayList<>();
        Set<TableFilter> remainingFilters = new HashSet<>(Arrays.asList(filters));

        while (!remainingFilters.isEmpty()) {
            TableFilter bestNext = selectNextTable(orderedFilters, remainingFilters);
            orderedFilters.add(bestNext);
            remainingFilters.remove(bestNext);
        }

        return orderedFilters.toArray(new TableFilter[0]);
    }

    private TableFilter selectNextTable(List<TableFilter> orderedFilters, Set<TableFilter> remainingFilters) {

        TableFilter bestCandidate = null;
        long lowestRowCount = Long.MAX_VALUE;

        for (TableFilter filter : remainingFilters) {
            if (!orderedFilters.isEmpty() && !hasJoinCondition(orderedFilters, filter)) {
                continue; 
            }

            long rowCount = filter.getTable().getRowCountApproximation(session);
            if (rowCount < lowestRowCount) {
                lowestRowCount = rowCount;
                bestCandidate = filter;
            }
        }

        return bestCandidate != null ? bestCandidate : remainingFilters.iterator().next();
    }

    private boolean hasJoinCondition(List<TableFilter> orderedFilters, TableFilter candidate) {

        for (TableFilter existing : orderedFilters) {
            Expression condition = existing.getFullCondition();
            if (condition != null && condition.toString().contains(candidate.getTable().getName())) {
                return true;
            }
        }

        return false;
    }
}