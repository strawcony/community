/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.impl.path;

import static org.neo4j.kernel.CommonSelectorOrdering.LEVEL_STOP_DESCENT_ON_RESULT;
import static org.neo4j.kernel.Traversal.shortestPathsCollisionDetector;
import static org.neo4j.kernel.Traversal.traversal;
import static org.neo4j.kernel.Uniqueness.NODE_PATH;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipExpander;
import org.neo4j.graphdb.traversal.Traverser;

public class TraversalShortestPath extends TraversalPathFinder
{
    private final RelationshipExpander expander;

    public TraversalShortestPath( RelationshipExpander expander, int maxDepth )
    {
        this.expander = expander;
    }
    
    @Override
    protected Traverser instantiateTraverser( Node start, Node end )
    {
        return traversal().breadthFirst().uniqueness( NODE_PATH ).expand( expander )
                .bidirectional( LEVEL_STOP_DESCENT_ON_RESULT, shortestPathsCollisionDetector(), end )
                .traverse( start );
    }
}
