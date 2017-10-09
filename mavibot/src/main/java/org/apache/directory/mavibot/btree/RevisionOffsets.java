/*
 *   Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */
package org.apache.directory.mavibot.btree;


import java.util.Arrays;


/**
 * A class to hold a revision, and copied page offsets of a B-Tree. This is used by the CPB.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class RevisionOffsets
{
    /** the revision number */
    private long revision;

    /** offsets of copied pages */
    private long[] offsets;


    /**
     * Creates a new instance of RevisionOffset.
     *
     * @param revision the revision number
     * @param offsets array of copied page offsets
     */
    public RevisionOffsets( long revision, long[] offsets )
    {
        this.revision = revision;
        this.offsets = offsets;
    }


    /**
     * @return The revison
     */
    public long getRevision()
    {
        return revision;
    }


    /**
     * @param revision The revision to set
     */
    /* no qualifier */void setRevision( long revision )
    {
        this.revision = revision;
    }


    /**
     * @return The list of copied page offsets
     */
    public long[] getOffsets()
    {
        return offsets;
    }


    /**
     * @param offsets The copied page offsets
     */
    /* no qualifier */void setOffsets( long[] offsets )
    {
        this.offsets = offsets;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode()
    {
        int prime = 31;
        int result = 17;
        result = prime * result + ( int ) revision;
        long offsetSum = 0L;

        for ( long offset : offsets )
        {
            offsetSum += offset;
        }
        
        result += ( int ) ( offsetSum * result );
        
        return result;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals( Object obj )
    {
        if ( this == obj )
        {
            return true;
        }
        
        if ( !( obj instanceof RevisionOffsets ) )
        {
            return false;
        }

        RevisionOffsets other = ( RevisionOffsets ) obj;

        return revision == other.revision;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "RevisionOffset [revision=" + revision + ", offsets=" + Arrays.toString( offsets ) + "]";
    }

}
