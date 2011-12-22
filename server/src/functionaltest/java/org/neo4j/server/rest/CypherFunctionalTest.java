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
package org.neo4j.server.rest;

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.matchers.JUnitMatchers.containsString;

import java.io.UnsupportedEncodingException;
import java.util.Map;

import javax.ws.rs.core.Response.Status;

import org.junit.Ignore;
import org.junit.Test;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.impl.annotations.Documented;
import org.neo4j.server.rest.domain.JsonHelper;
import org.neo4j.test.GraphDescription;
import org.neo4j.test.GraphDescription.Graph;
import org.neo4j.test.GraphDescription.NODE;
import org.neo4j.test.GraphDescription.PROP;
import org.neo4j.test.GraphDescription.REL;
import org.neo4j.test.TestData.Title;

public class CypherFunctionalTest extends AbstractRestFunctionalTestBase {

    
    /**
     * A simple query returning all nodes connected to node 1, returning the
     * node and the name property, if it exists, otherwise `null`:
     */
    @Test
    @Documented
    @Title( "Send a Query" )
    @Graph( nodes = {
            @NODE( name = "I", setNameProperty = true ),
            @NODE( name = "you", setNameProperty = true ),
            @NODE( name = "him", setNameProperty = true, properties = {
                    @PROP( key = "age", value = "25", type = GraphDescription.PropType.INTEGER ) } ) },
            relationships = {
                    @REL( start = "I", end = "him", type = "know", properties = { } ),
                    @REL( start = "I", end = "you", type = "know", properties = { } ) } )
    public void testPropertyColumn() throws UnsupportedEncodingException {
        String script = createScript( "start x  = node(%I%) match x -[r]-> n return type(r), n.name?, n.age?" );

        String response = cypherRestCall( script, Status.OK );

        assertThat( response, containsString( "you" ) );
        assertThat( response, containsString( "him" ) );
        assertThat( response, containsString( "25" ) );
        assertThat( response, not( containsString( "\"x\"" ) ) );
    }


    /**
     * Errors on the server will be reported as a JSON-formatted stacktrace and
     * message.
     */
    @Test
    @Documented
    @Title( "Server errors" )
    @Graph( "I know you" )
    public void error_gets_returned_as_json() throws Exception {
        String response = cypherRestCall( "start x = node(%I%) return x.dummy", Status.BAD_REQUEST );
        assertEquals( 3, ( JsonHelper.jsonToMap( response ) ).size() );
    }


    /**
     * Paths can be returned
     * together with other return types by just
     * specifying returns.
     */
    @Test
    @Documented
    @Graph( "I know you" )
    public void return_paths() throws Exception {
        String script = "start x  = node(%I%) match path = (x--friend) return path, friend.name";
        String response = cypherRestCall( script, Status.OK );

        assertEquals( 2, ( JsonHelper.jsonToMap( response ) ).size() );
        assertThat( response, containsString( "data" ) );
        assertThat( response, containsString( "you" ) );
    }

    /**
     * Cypher supports queries with parameters
     * which are submitted as a JSON map.
     */
    @Test
    @Documented
    @Graph( value = { "I know you" }, autoIndexNodes = true )
    public void send_queries_with_parameters() throws Exception {
        data.get();
        String script = "start x  = node:node_auto_index(name={startName}) match path = (x-[r]-friend) where friend" +
                ".name = {name} return TYPE(r)";
        String response = cypherRestCall( script, Status.OK, Pair.of( "startName", "I" ), Pair.of( "name", "you" ) );


        assertEquals( 2, ( JsonHelper.jsonToMap( response ) ).size() );
        assertTrue( response.contains( "know" ) );
        assertTrue( response.contains( "data" ) );
    }
    
    @Test
    @Documented
    @Graph( value = { "I know you" }, autoIndexNodes = true )
    public void send_queries_with_syntax_errors() throws Exception {
        data.get();
        String script = "start x  = node:node_auto_index(name={startName}) matc path = (x-[r]-friend) where friend" +
                ".name = {name} return TYPE(r)";
        String response = cypherRestCall( script, Status.BAD_REQUEST, Pair.of( "startName", "I" ), Pair.of( "name", "you" ) );


        assertEquals( 3, ( JsonHelper.jsonToMap( response ) ).size() );
        assertTrue( response.contains( "message" ) );
    }
    
    /**
     * When sending queries that
     * return nested results like list and maps,
     * these will get serialized into nested JSON representations
     * according to their types.
     */
    @Test
    @Documented
    @Graph( value = { "I know you" }, autoIndexNodes = true )
    public void nested_results() throws Exception {
        data.get();
        String script = "start n = node(%I%,%you%) return collect(n.name), collect(n)";
        String response = cypherRestCall( script, Status.OK);


        Map<String, Object> resultMap = JsonHelper.jsonToMap( response );
        assertEquals( 2, resultMap.size() );
        assertTrue( response.contains( "[ [ [ \"I\"" ) );
    }

    @Test
    @Documented
    @Ignore
    @Graph( value = { "I know you" }, autoIndexNodes = true )
    public void send_queries_with_errors() throws Exception {
        data.get();
        String script = "start x  = node:node_auto_index(name={startName}) match path = (x-[r]-friend) where frien" +
                ".name = {name} return TYPE(r)";
        String response = cypherRestCall( script, Status.BAD_REQUEST, Pair.of( "startName", "I" ), Pair.of( "name", "you" ) );


        assertEquals( 3, ( JsonHelper.jsonToMap( response ) ).size() );
        assertTrue( response.contains( "message" ) );
    }

    private String cypherRestCall( String script, Status status,
            Pair<String, String> ...params )
    {
        return super.doCypherRestCall( cypherUri(), script, status, params );
    }
    

    private String cypherUri()
    {
        return getDataUri() + "cypher";
    }
    
}
