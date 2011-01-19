/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexManager;
import org.neo4j.graphdb.index.IndexProvider;
import org.neo4j.graphdb.index.RelationshipIndex;
import org.neo4j.helpers.Pair;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.kernel.impl.index.IndexStore;
import org.neo4j.kernel.impl.index.IndexXaConnection;
import org.neo4j.kernel.impl.transaction.xaframework.XaDataSource;

class IndexManagerImpl implements IndexManager
{
    private static final String KEY_INDEX_PROVIDER = "provider";

    private final IndexStore indexStore;
    private final Map<String, IndexProvider> indexProviders = new HashMap<String, IndexProvider>();

    private final EmbeddedGraphDbImpl graphDbImpl;

    IndexManagerImpl( EmbeddedGraphDbImpl graphDbImpl, IndexStore indexStore )
    {
        this.graphDbImpl = graphDbImpl;
        this.indexStore = indexStore;
    }

    private IndexProvider getIndexProvider( String provider )
    {
        if ( provider == null )
        {
            throw new IllegalArgumentException( "No 'provider' given in configuration map" );
        }

        synchronized ( this.indexProviders )
        {
            IndexProvider result = this.indexProviders.get( provider );
            if ( result != null )
            {
                return result;
            }
            throw new IllegalArgumentException( "No index provider '" + provider + "' found" );
        }
    }

    void addProvider( String name, IndexProvider provider )
    {
        this.indexProviders.put( name, provider );
    }

    private Pair<Map<String, String>, Boolean> findIndexConfig( Class<? extends PropertyContainer> cls,
            String indexName, Map<String, String> suppliedConfig, Map<?, ?> dbConfig )
    {
        // Check stored config (has this index been created previously?)
        Map<String, String> storedConfig = indexStore.get( cls, indexName );
        if ( storedConfig != null && suppliedConfig == null )
        {
            // Fill in "provider" if not already filled in, backwards compatibility issue
            Map<String, String> newConfig = injectDefaultProviderIfMissing( cls, indexName, dbConfig, storedConfig );
            if ( newConfig != storedConfig )
            {
                indexStore.set( cls, indexName, newConfig );
            }
            return Pair.of( newConfig, Boolean.FALSE );
        }

        Map<String, String> configToUse = suppliedConfig;

        // Check db config properties for provider
        String provider = null;
        IndexProvider indexProvider = null;
        if ( configToUse == null )
        {
            provider = getDefaultProvider( indexName, dbConfig );
            indexProvider = getIndexProvider( provider );
            configToUse = indexProvider.fillInDefaults( MapUtil.stringMap( KEY_INDEX_PROVIDER, provider ) );
        }
        else
        {
            provider = configToUse.get( KEY_INDEX_PROVIDER );
            provider = provider == null ? getDefaultProvider( indexName, dbConfig ) : provider;
            indexProvider = getIndexProvider( provider );
        }
        configToUse = injectDefaultProviderIfMissing( cls, indexName, dbConfig, configToUse );

        // Do they match (stored vs. supplied)?
        if ( storedConfig != null )
        {
            if ( suppliedConfig != null && !indexProvider.configMatches( storedConfig, suppliedConfig ) )
            {
                throw new IllegalArgumentException( "Supplied index configuration:\n" +
                        suppliedConfig + "\ndoesn't match stored config in a valid way:\n" + storedConfig +
                        "\nfor '" + indexName + "'" );
            }
            // Fill in "provider" if not already filled in, backwards compatibility issue
            Map<String, String> newConfig = injectDefaultProviderIfMissing( cls, indexName, dbConfig, storedConfig );
            if ( newConfig != storedConfig )
            {
                indexStore.set( cls, indexName, newConfig );
            }
            configToUse = newConfig;
        }

        boolean created = indexStore.setIfNecessary( cls, indexName, configToUse );
        return Pair.of( Collections.unmodifiableMap( configToUse ), created );
    }

    private Map<String, String> injectDefaultProviderIfMissing(
            Class<? extends PropertyContainer> cls, String indexName, Map<?, ?> dbConfig,
            Map<String, String> config )
    {
        String provider = config.get( KEY_INDEX_PROVIDER );
        if ( provider == null )
        {
            config = new HashMap<String, String>( config );
            config.put( KEY_INDEX_PROVIDER, getDefaultProvider( indexName, dbConfig ) );
        }
        return config;
    }

    private String getDefaultProvider( String indexName, Map<?, ?> dbConfig )
    {
        String provider = null;
        if ( dbConfig != null )
        {
            provider = (String) dbConfig.get( "index." + indexName );
            if ( provider == null )
            {
                provider = (String) dbConfig.get( "index" );
            }
        }

        // 4. Default to lucene
        if ( provider == null )
        {
            provider = "lucene";
        }
        return provider;
    }

    private Map<String, String> getOrCreateIndexConfig( Class<? extends PropertyContainer> cls,
            String indexName, Map<String, String> suppliedConfig )
    {
        Pair<Map<String, String>, Boolean> result = findIndexConfig( cls,
                indexName, suppliedConfig, graphDbImpl.getConfig().getParams() );
        if ( result.other() )
        {
            IndexCreatorThread creator = new IndexCreatorThread( cls, indexName, result.first() );
            creator.start();
            try
            {
                creator.join();
                if ( creator.exception != null )
                {
                    throw new TransactionFailureException( "Index creation failed for " + indexName +
                            ", " + result.first(), creator.exception );
                }
            }
            catch ( InterruptedException e )
            {
                Thread.interrupted();
            }
        }
        return result.first();
    }

    private class IndexCreatorThread extends Thread
    {
        private final String indexName;
        private final Map<String, String> config;
        private Exception exception;
        private final Class<? extends PropertyContainer> cls;

        IndexCreatorThread( Class<? extends PropertyContainer> cls, String indexName,
                Map<String, String> config )
        {
            this.cls = cls;
            this.indexName = indexName;
            this.config = config;
        }

        @Override
        public void run()
        {
            String provider = config.get( KEY_INDEX_PROVIDER );
            String dataSourceName = getIndexProvider( provider ).getDataSourceName();
            XaDataSource dataSource = graphDbImpl.getConfig().getTxModule().getXaDataSourceManager().getXaDataSource( dataSourceName );
            IndexXaConnection connection = (IndexXaConnection) dataSource.getXaConnection();
            Transaction tx = graphDbImpl.beginTx();
            try
            {
                javax.transaction.Transaction javaxTx = graphDbImpl.getConfig().getTxModule().getTxManager().getTransaction();
                javaxTx.enlistResource( connection.getXaResource() );
                connection.createIndex( cls, indexName, config );
                tx.success();
            }
            catch ( Exception e )
            {
                this.exception = e;
            }
            finally
            {
                tx.finish();
            }
        }
    }

    public boolean existsForNodes( String indexName )
    {
        return indexStore.get( Node.class, indexName ) != null;
    }

    public Index<Node> forNodes( String indexName )
    {
        Map<String, String> config = getOrCreateIndexConfig( Node.class, indexName, null );
        return getIndexProvider( config.get( KEY_INDEX_PROVIDER ) ).nodeIndex( indexName, config );
    }

    public Index<Node> forNodes( String indexName, Map<String, String> customConfiguration )
    {
        Map<String, String> config = getOrCreateIndexConfig( Node.class, indexName,
                customConfiguration );
        return getIndexProvider( config.get( KEY_INDEX_PROVIDER ) ).nodeIndex( indexName, config );
    }

    public String[] nodeIndexNames()
    {
        return indexStore.getNames( Node.class );
    }

    public boolean existsForRelationships( String indexName )
    {
        return indexStore.get( Relationship.class, indexName ) != null;
    }

    public RelationshipIndex forRelationships( String indexName )
    {
        Map<String, String> config = getOrCreateIndexConfig( Relationship.class, indexName, null );
        return getIndexProvider( config.get( KEY_INDEX_PROVIDER ) ).relationshipIndex( indexName,
                config );
    }

    public RelationshipIndex forRelationships( String indexName,
            Map<String, String> customConfiguration )
    {
        Map<String, String> config = getOrCreateIndexConfig( Relationship.class, indexName,
                customConfiguration );
        return getIndexProvider( config.get( KEY_INDEX_PROVIDER ) ).relationshipIndex( indexName,
                config );
    }

    public String[] relationshipIndexNames()
    {
        return indexStore.getNames( Relationship.class );
    }

    public Map<String, String> getConfiguration( Index<? extends PropertyContainer> index )
    {
        Map<String, String> config = indexStore.get( index.getEntityType(), index.getName() );
        if ( config == null )
        {
            throw new NotFoundException( "No " + index.getEntityType().getSimpleName() +
                    " index '" + index.getName() + "' found" );
        }
        return config;
    }

    public String setConfiguration( Index<? extends PropertyContainer> index, String key, String value )
    {
        assertLegalConfigKey( key );
        Map<String, String> config = getMutableConfig( index );
        String oldValue = config.put( key, value );
        indexStore.set( index.getEntityType(), index.getName(), config );
        return oldValue;
    }

    private void assertLegalConfigKey( String key )
    {
        if ( key.equals( KEY_INDEX_PROVIDER ) )
        {
            throw new IllegalArgumentException( "'" + key + "' cannot be modified" );
        }
    }

    private Map<String, String> getMutableConfig( Index<? extends PropertyContainer> index )
    {
        return new HashMap<String, String>( getConfiguration( index ) );
    }

    public String removeConfiguration( Index<? extends PropertyContainer> index, String key )
    {
        assertLegalConfigKey( key );
        Map<String, String> config = getMutableConfig( index );
        String value = config.remove( key );
        if ( value != null )
        {
            indexStore.set( index.getEntityType(), index.getName(), config );
        }
        return value;
    }
}
