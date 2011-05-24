package org.apache.maven.repository;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.router.ArtifactRouter;
import org.apache.maven.artifact.router.MirrorRoute;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.settings.Mirror;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.StringUtils;

@Component( role = MirrorSelector.class )
public class DefaultMirrorSelector
    implements MirrorSelector
{

    private static final String WILDCARD = "*";

    private static final String EXTERNAL_WILDCARD = "external:*";
    
    @Requirement
    private Logger logger;
    
    @Requirement
    private LegacySupport legacySupport;

    public Mirror getMirror( final ArtifactRepository repository, final List<Mirror> mirrors )
    {
        if ( logger.isDebugEnabled() )
        {
            logger.debug( "MAVEN-SELECT: " + repository.getUrl() );
        }

        Mirror mirror = getSettingsMirror( repository, mirrors );

        if ( mirror != null )
        {
            if ( logger.isDebugEnabled() )
            {
                logger.debug( "MAVEN-SELECT using mirror from settings.xml." );
            }

            return mirror;
        }

        // FIXME: I suspect we need a better approach than simply punting when the session hasn't 
        // been set. This will nullify the mirror selection taking place in the 
        // DefaultMavenExecutionRequestPopulator.
        if ( mirror == null && legacySupport.getSession() != null )
        {
            final String repoUrl = repository.getUrl();

            ArtifactRouter routingTable = legacySupport.getSession().getMirrorRouter();
            final MirrorRoute rMirror = routingTable.getMirror( repoUrl );

            if ( rMirror != null )
            {
                if ( logger.isDebugEnabled() )
                {
                    logger.debug( "\t==> " + rMirror );
                }

                mirror = new Mirror();
                mirror.setMirrorOf( repository.getId() );
                
                // FIXME!
                mirror.setLayout( "default" );
                mirror.setId( rMirror.getId() );
                mirror.setUrl( rMirror.getRouteUrl() );
            }
            else
            {
                if ( logger.isDebugEnabled() )
                {
                    logger.debug( "MAVEN-SELECT: no auto-mirror found." );
                }
            }
        }

        return mirror;
    }
    
    private Mirror getSettingsMirror( ArtifactRepository repository, List<Mirror> mirrors )
    {
        String repoId = repository.getId();

        if ( repoId != null && mirrors != null )
        {
            for ( Mirror mirror : mirrors )
            {
                if ( repoId.equals( mirror.getMirrorOf() ) && matchesLayout( repository, mirror ) )
                {
                    return mirror;
                }
            }

            for ( Mirror mirror : mirrors )
            {
                if ( matchPattern( repository, mirror.getMirrorOf() ) && matchesLayout( repository, mirror ) )
                {
                    return mirror;
                }
            }
        }

        return null;
    }

    /**
     * This method checks if the pattern matches the originalRepository. Valid patterns: * =
     * everything external:* = everything not on the localhost and not file based. repo,repo1 = repo
     * or repo1 *,!repo1 = everything except repo1
     *
     * @param originalRepository to compare for a match.
     * @param pattern used for match. Currently only '*' is supported.
     * @return true if the repository is a match to this pattern.
     */
    static boolean matchPattern( ArtifactRepository originalRepository, String pattern )
    {
        boolean result = false;
        String originalId = originalRepository.getId();

        // simple checks first to short circuit processing below.
        if ( WILDCARD.equals( pattern ) || pattern.equals( originalId ) )
        {
            result = true;
        }
        else
        {
            // process the list
            String[] repos = pattern.split( "," );
            for ( String repo : repos )
            {
                // see if this is a negative match
                if ( repo.length() > 1 && repo.startsWith( "!" ) )
                {
                    if ( repo.substring( 1 ).equals( originalId ) )
                    {
                        // explicitly exclude. Set result and stop processing.
                        result = false;
                        break;
                    }
                }
                // check for exact match
                else if ( repo.equals( originalId ) )
                {
                    result = true;
                    break;
                }
                // check for external:*
                else if ( EXTERNAL_WILDCARD.equals( repo ) && isExternalRepo( originalRepository ) )
                {
                    result = true;
                    // don't stop processing in case a future segment explicitly excludes this repo
                }
                else if ( WILDCARD.equals( repo ) )
                {
                    result = true;
                    // don't stop processing in case a future segment explicitly excludes this repo
                }
            }
        }
        return result;
    }

    /**
     * Checks the URL to see if this repository refers to an external repository
     *
     * @param originalRepository
     * @return true if external.
     */
    static boolean isExternalRepo( ArtifactRepository originalRepository )
    {
        try
        {
            URL url = new URL( originalRepository.getUrl() );
            return !( url.getHost().equals( "localhost" ) || url.getHost().equals( "127.0.0.1" )
                            || url.getProtocol().equals( "file" ) );
        }
        catch ( MalformedURLException e )
        {
            // bad url just skip it here. It should have been validated already, but the wagon lookup will deal with it
            return false;
        }
    }

    static boolean matchesLayout( ArtifactRepository repository, Mirror mirror )
    {
        return matchesLayout( RepositoryUtils.getLayout( repository ), mirror.getMirrorOfLayouts() );
    }

    /**
     * Checks whether the layouts configured for a mirror match with the layout of the repository.
     *
     * @param repoLayout The layout of the repository, may be {@code null}.
     * @param mirrorLayout The layouts supported by the mirror, may be {@code null}.
     * @return {@code true} if the layouts associated with the mirror match the layout of the original repository,
     *         {@code false} otherwise.
     */
    static boolean matchesLayout( String repoLayout, String mirrorLayout )
    {
        boolean result = false;

        // simple checks first to short circuit processing below.
        if ( StringUtils.isEmpty( mirrorLayout ) || WILDCARD.equals( mirrorLayout ) )
        {
            result = true;
        }
        else if ( mirrorLayout.equals( repoLayout ) )
        {
            result = true;
        }
        else
        {
            // process the list
            String[] layouts = mirrorLayout.split( "," );
            for ( String layout : layouts )
            {
                // see if this is a negative match
                if ( layout.length() > 1 && layout.startsWith( "!" ) )
                {
                    if ( layout.substring( 1 ).equals( repoLayout ) )
                    {
                        // explicitly exclude. Set result and stop processing.
                        result = false;
                        break;
                    }
                }
                // check for exact match
                else if ( layout.equals( repoLayout ) )
                {
                    result = true;
                    break;
                }
                else if ( WILDCARD.equals( layout ) )
                {
                    result = true;
                    // don't stop processing in case a future segment explicitly excludes this repo
                }
            }
        }

        return result;
    }

}
