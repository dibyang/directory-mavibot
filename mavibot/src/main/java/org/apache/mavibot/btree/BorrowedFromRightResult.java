/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mavibot.btree;


/**
 * The result of a delete operation, when the child has not been merged. It contains the
 * reference to the modified page, and the removed element.
 * 
 * @param <K> The type for the Key
 * @param <V> The type for the stored value

 * @author <a href="mailto:labs@labs.apache.org">Mavibot labs Project</a>
 */
/* No qualifier */class BorrowedFromRightResult<K, V> extends AbstractBorrowedFromSiblingResult<K, V>
{
    /**
     * The default constructor for BorrowedFromRightResult.
     * 
     * @param modifiedPage The modified page
     * @param modifiedSibling The modified sibling
     * @param removedElement The removed element (can be null if the key wasn't present in the tree)
     */
    /* No qualifier */BorrowedFromRightResult( Page<K, V> modifiedPage, Page<K, V> modifiedSibling,
        Tuple<K, V> removedElement )
    {
        super( modifiedPage, modifiedSibling, removedElement, AbstractBorrowedFromSiblingResult.SiblingPosition.RIGHT );
    }


    /**
     * @see Object#toString()
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        sb.append( "Borrowed from right" );
        sb.append( super.toString() );

        return sb.toString();
    }
}
