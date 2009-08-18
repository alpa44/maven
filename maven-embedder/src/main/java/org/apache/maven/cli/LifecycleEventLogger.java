package org.apache.maven.cli;

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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.apache.maven.embedder.MavenEmbedderLogger;
import org.apache.maven.execution.BuildFailure;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.BuildSummary;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.AbstractLifecycleListener;
import org.apache.maven.lifecycle.LifecycleEvent;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;

/**
 * Logs lifecycle events to a user-supplied logger.
 * 
 * @author Benjamin Bentmann
 */
class LifecycleEventLogger
    extends AbstractLifecycleListener
{

    private final MavenEmbedderLogger logger;

    private static final int LINE_LENGTH = 72;

    public LifecycleEventLogger( MavenEmbedderLogger logger )
    {
        if ( logger == null )
        {
            throw new IllegalArgumentException( "logger missing" );
        }

        this.logger = logger;
    }

    private static String chars( char c, int count )
    {
        StringBuilder buffer = new StringBuilder( count );

        for ( int i = count; i > 0; i-- )
        {
            buffer.append( c );
        }

        return buffer.toString();
    }

    private static String getFormattedTime( long time )
    {
        String pattern = "s.SSS's'";

        if ( time / 60000L > 0 )
        {
            pattern = "m:s" + pattern;

            if ( time / 3600000L > 0 )
            {
                pattern = "H:m" + pattern;
            }
        }

        DateFormat fmt = new SimpleDateFormat( pattern );
        fmt.setTimeZone( TimeZone.getTimeZone( "UTC" ) );

        return fmt.format( new Date( time ) );
    }

    @Override
    public void sessionStarted( LifecycleEvent event )
    {
        if ( logger.isInfoEnabled() )
        {
            logger.info( "Build Order:" );

            logger.info( "" );

            for ( MavenProject project : event.getSession().getProjects() )
            {
                logger.info( project.getName() );
            }

            logger.info( "" );
        }
    }

    @Override
    public void sessionEnded( LifecycleEvent event )
    {
        if ( logger.isInfoEnabled() )
        {
            if ( event.getSession().getProjects().size() > 1 )
            {
                logReactorSummary( event.getSession() );
            }

            logResult( event.getSession() );

            logStats( event.getSession() );

            logger.info( chars( '-', LINE_LENGTH ) );
        }
    }

    private void logReactorSummary( MavenSession session )
    {
        logger.info( chars( '-', LINE_LENGTH ) );
        logger.info( "Reactor Summary:" );
        logger.info( chars( '-', LINE_LENGTH ) );

        MavenExecutionResult result = session.getResult();

        for ( MavenProject project : session.getProjects() )
        {
            StringBuilder buffer = new StringBuilder( 128 );

            buffer.append( project.getName() );

            buffer.append( ' ' );
            while ( buffer.length() < LINE_LENGTH - 21 )
            {
                buffer.append( '.' );
            }
            buffer.append( ' ' );

            BuildSummary buildSummary = result.getBuildSummary( project );

            if ( buildSummary == null )
            {
                buffer.append( "SKIPPED" );
            }
            else if ( buildSummary instanceof BuildSuccess )
            {
                buffer.append( "SUCCESS [" );
                buffer.append( getFormattedTime( buildSummary.getTime() ) );
                buffer.append( "]" );
            }
            else if ( buildSummary instanceof BuildFailure )
            {
                buffer.append( "FAILURE [" );
                buffer.append( getFormattedTime( buildSummary.getTime() ) );
                buffer.append( "]" );
            }

            logger.info( buffer.toString() );
        }
    }

    private void logResult( MavenSession session )
    {
        logger.info( chars( '-', LINE_LENGTH ) );

        if ( session.getResult().hasExceptions() )
        {
            logger.info( "BUILD FAILURE" );
        }
        else
        {
            logger.info( "BUILD SUCCESS" );
        }
    }

    private void logStats( MavenSession session )
    {
        logger.info( chars( '-', LINE_LENGTH ) );

        Date finish = new Date();

        long time = finish.getTime() - session.getRequest().getStartTime().getTime();

        logger.info( "Total time: " + getFormattedTime( time ) );

        logger.info( "Finished at: " + finish );

        System.gc();

        Runtime r = Runtime.getRuntime();

        long MB = 1024 * 1024;

        logger.info( "Final Memory: " + ( r.totalMemory() - r.freeMemory() ) / MB + "M/" + r.totalMemory() / MB + "M" );
    }

    @Override
    public void projectSkipped( LifecycleEvent event )
    {
        if ( logger.isInfoEnabled() )
        {
            logger.info( "Skipping " + event.getProject().getName() );
            logger.info( "This project has been banned from the build due to previous failures." );
        }
    }

    @Override
    public void projectStarted( LifecycleEvent event )
    {
        if ( logger.isInfoEnabled() )
        {
            logger.info( chars( '-', LINE_LENGTH ) );
            logger.info( "Building " + event.getProject().getName() );
            logger.info( chars( '-', LINE_LENGTH ) );
        }
    }

    @Override
    public void mojoSkipped( LifecycleEvent event )
    {
        if ( logger.isWarnEnabled() )
        {
            logger.warn( "Goal " + event.getMojoExecution().getMojoDescriptor().getGoal()
                + " requires online mode for execution but Maven is currently offline, skipping" );
        }
    }

    @Override
    public void mojoStarted( LifecycleEvent event )
    {
        if ( logger.isInfoEnabled() )
        {
            MojoDescriptor md = event.getMojoExecution().getMojoDescriptor();
            PluginDescriptor pd = md.getPluginDescriptor();
            logger.info( "Executing " + pd.getArtifactId() + ':' + pd.getVersion() + ':' + md.getGoal() + " on "
                + event.getProject().getArtifactId() );
        }
    }

    @Override
    public void forkStarted( LifecycleEvent event )
    {
        if ( logger.isDebugEnabled() )
        {
            logger.debug( "Forking execution for " + event.getMojoExecution().getMojoDescriptor().getId() );
        }
    }

    @Override
    public void forkSucceeded( LifecycleEvent event )
    {
        if ( logger.isDebugEnabled() )
        {
            logger.debug( "Completed forked execution for " + event.getMojoExecution().getMojoDescriptor().getId() );
        }
    }

}