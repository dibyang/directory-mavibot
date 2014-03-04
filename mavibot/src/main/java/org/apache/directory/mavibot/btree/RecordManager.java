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
package org.apache.directory.mavibot.btree;


import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.directory.mavibot.btree.exception.BTreeAlreadyManagedException;
import org.apache.directory.mavibot.btree.exception.BTreeCreationException;
import org.apache.directory.mavibot.btree.exception.EndOfFileExceededException;
import org.apache.directory.mavibot.btree.exception.FileException;
import org.apache.directory.mavibot.btree.exception.FreePageException;
import org.apache.directory.mavibot.btree.exception.InvalidBTreeException;
import org.apache.directory.mavibot.btree.exception.InvalidOffsetException;
import org.apache.directory.mavibot.btree.exception.KeyNotFoundException;
import org.apache.directory.mavibot.btree.exception.RecordManagerException;
import org.apache.directory.mavibot.btree.serializer.ElementSerializer;
import org.apache.directory.mavibot.btree.serializer.IntSerializer;
import org.apache.directory.mavibot.btree.serializer.LongArraySerializer;
import org.apache.directory.mavibot.btree.serializer.LongSerializer;
import org.apache.directory.mavibot.btree.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The RecordManager is used to manage the file in which we will store the B-trees.
 * A RecordManager will manage more than one B-tree.<br/>
 *
 * It stores data in fixed size pages (default size is 512 bytes), which may be linked one to
 * the other if the data we want to store is too big for a page.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class RecordManager
{
    /** The LoggerFactory used by this class */
    protected static final Logger LOG = LoggerFactory.getLogger( RecordManager.class );

    /** The LoggerFactory used by this class */
    protected static final Logger LOG_PAGES = LoggerFactory.getLogger( "LOG_PAGES" );

    /** A dedicated logger for the check */
    protected static final Logger LOG_CHECK = LoggerFactory.getLogger( "LOG_CHECK" );

    /** The associated file */
    private File file;

    /** The channel used to read and write data */
    private FileChannel fileChannel;

    /** The number of managed B-trees */
    private int nbBtree;

    /** The first and last free page */
    private long firstFreePage;

    /** The list of available free pages */
    List<PageIO> freePages = new ArrayList<PageIO>();

    /** Some counters to track the number of free pages */
    public AtomicLong nbFreedPages = new AtomicLong( 0 );
    public AtomicLong nbCreatedPages = new AtomicLong( 0 );
    public AtomicLong nbReusedPages = new AtomicLong( 0 );
    public AtomicLong nbUpdateRMHeader = new AtomicLong( 0 );
    public AtomicLong nbUpdateBtreeHeader = new AtomicLong( 0 );
    public AtomicLong nbUpdatePageIOs = new AtomicLong( 0 );

    /** The offset of the end of the file */
    private long endOfFileOffset;

    /**
     * A B-tree used to manage the page that has been copied in a new version.
     * Those pages can be reclaimed when the associated version is dead.
     **/
    private BTree<RevisionName, long[]> copiedPageBtree;

    /** A constant for an offset on a non existing page */
    private static final long NO_PAGE = -1L;

    /** The number of element we can store in a page */
    private static final int PAGE_SIZE = 4;

    /** The size of the link to next page */
    private static final int LINK_SIZE = 8;

    /** Some constants */
    private static final int BYTE_SIZE = 1;
    private static final int INT_SIZE = 4;
    private static final int LONG_SIZE = 8;

    /** The default page size */
    private static final int DEFAULT_PAGE_SIZE = 512;

    /** The minimal page size. Can't be below 64, as we have to store many thing sin the RMHeader */
    private static final int MIN_PAGE_SIZE = 64;

    /** The RecordManager header size */
    private static int RECORD_MANAGER_HEADER_SIZE = DEFAULT_PAGE_SIZE;

    /** A global buffer used to store the RecordManager header */
    private static ByteBuffer RECORD_MANAGER_HEADER_BUFFER;

    /** A static buffer used to store the RecordManager header */
    private static byte[] RECORD_MANAGER_HEADER_BYTES;

    /** The length of an Offset, as a negative value */
    private static byte[] LONG_LENGTH = new byte[]
        { ( byte ) 0xFF, ( byte ) 0xFF, ( byte ) 0xFF, ( byte ) 0xF8 };

    /** The RecordManager underlying page size. */
    private int pageSize = DEFAULT_PAGE_SIZE;

    /** The set of managed B-trees */
    private Map<String, BTree<Object, Object>> managedBtrees;

    /** The default file name */
    private static final String DEFAULT_FILE_NAME = "mavibot.db";

    /** A flag set to true if we want to keep old revisions */
    private boolean keepRevisions;

    /** A flag used by internal btrees */
    public static final boolean INTERNAL_BTREE = true;

    /** A flag used by internal btrees */
    public static final boolean NORMAL_BTREE = false;

    /** The B-tree of B-trees */
    private BTree<NameRevision, Long> btreeOfBtrees;

    /** The B-tree of B-trees management btree name */
    private static final String BTREE_OF_BTREES_NAME = "_btree_of_btrees_";

    /** The CopiedPages management btree name */
    private static final String COPIED_PAGE_BTREE_NAME = "_copiedPageBtree_";

    /** The current B-tree of B-trees header offset */
    private long currentBtreeOfBtreesOffset;

    /** The previous B-tree of B-trees header offset */
    private long previousBtreeOfBtreesOffset = NO_PAGE;

    /** The offset on the current copied pages B-tree */
    private long currentCopiedPagesBtreeOffset = NO_PAGE;

    /** The offset on the previous copied pages B-tree */
    private long previousCopiedPagesBtreeOffset = NO_PAGE;

    /** A lock to protect the transaction handling */
    private Lock transactionLock = new ReentrantLock();

    /** A counter used to remember how many times a transaction has been started */
    private int lockHeldCounter = 0;

    /** The list of PageIO that can be freed after a commit */
    List<PageIO> freedPages = new ArrayList<PageIO>();

    /** The list of PageIO that can be freed after a roolback */
    private List<PageIO> allocatedPages = new ArrayList<PageIO>();

    /**
     * Create a Record manager which will either create the underlying file
     * or load an existing one. If a folder is provided, then we will create
     * a file with a default name : mavibot.db
     *
     * @param name The file name, or a folder name
     */
    public RecordManager( String fileName )
    {
        this( fileName, DEFAULT_PAGE_SIZE );
    }


    /**
     * Create a Record manager which will either create the underlying file
     * or load an existing one. If a folder is provider, then we will create
     * a file with a default name : mavibot.db
     *
     * @param name The file name, or a folder name
     * @param pageSize the size of a page on disk, in bytes
     */
    public RecordManager( String fileName, int pageSize )
    {
        managedBtrees = new LinkedHashMap<String, BTree<Object, Object>>();

        if ( pageSize < MIN_PAGE_SIZE )
        {
            this.pageSize = MIN_PAGE_SIZE;
        }
        else
        {
            this.pageSize = pageSize;
        }

        RECORD_MANAGER_HEADER_BUFFER = ByteBuffer.allocate( this.pageSize );
        RECORD_MANAGER_HEADER_BYTES = new byte[this.pageSize];
        RECORD_MANAGER_HEADER_SIZE = this.pageSize;

        // Open the file or create it
        File tmpFile = new File( fileName );

        if ( tmpFile.isDirectory() )
        {
            // It's a directory. Check that we don't have an existing mavibot file
            tmpFile = new File( tmpFile, DEFAULT_FILE_NAME );
        }

        // We have to create a new file, if it does not already exist
        boolean isNewFile = createFile( tmpFile );

        try
        {
            RandomAccessFile randomFile = new RandomAccessFile( file, "rw" );
            fileChannel = randomFile.getChannel();

            // get the current end of file offset
            endOfFileOffset = fileChannel.size();

            if ( isNewFile )
            {
                initRecordManager();
            }
            else
            {
                loadRecordManager();
            }
        }
        catch ( Exception e )
        {
            LOG.error( "Error while initializing the RecordManager : {}", e.getMessage() );
            LOG.error( "", e );
            throw new RecordManagerException( e );
        }
    }


    /**
     * Create the mavibot file if it does not exist
     */
    private boolean createFile( File mavibotFile )
    {
        try
        {
            boolean creation = mavibotFile.createNewFile();

            file = mavibotFile;

            if ( mavibotFile.length() == 0 )
            {
                return true;
            }
            else
            {
                return creation;
            }
        }
        catch ( IOException ioe )
        {
            LOG.error( "Cannot create the file {}", mavibotFile.getName() );
            return false;
        }
    }


    /**
     * We will create a brand new RecordManager file, containing nothing, but the RecordManager header,
     * a B-tree to manage the old revisions we want to keep and
     * a B-tree used to manage pages associated with old versions.
     * <br/>
     * The RecordManager header contains the following details :
     * <pre>
     * +--------------------------+
     * | PageSize                 | 4 bytes : The size of a physical page (default to 4096)
     * +--------------------------+
     * |  NbTree                  | 4 bytes : The number of managed B-trees (at least 1)
     * +--------------------------+
     * | FirstFree                | 8 bytes : The offset of the first free page
     * +--------------------------+
     * | current BoB offset       | 8 bytes : The offset of the current BoB
     * +--------------------------+
     * | previous BoB offset      | 8 bytes : The offset of the previous BoB
     * +--------------------------+
     * | current CP btree offset  | 8 bytes : The offset of the current BoB
     * +--------------------------+
     * | previous CP btree offset | 8 bytes : The offset of the previous BoB
     * +--------------------------+
     * </pre>
     *
     * We then store the B-tree managing the pages that have been copied when we have added
     * or deleted an element in the B-tree. They are associated with a version.
     *
     * Last, we add the bTree that keep a track on each revision we can have access to.
     */
    private void initRecordManager() throws IOException
    {
        // Create a new Header
        nbBtree = 0;
        firstFreePage = NO_PAGE;
        currentBtreeOfBtreesOffset = 0L;

        updateRecordManagerHeader();

        // Set the offset of the end of the file
        endOfFileOffset = fileChannel.size();

        // First, create the btree of btrees <NameRevision, Long>
        createBtreeOfBtrees();

        // Now, initialize the Copied Page B-tree
        createCopiedPagesBtree();

        // Inject these B-trees into the RecordManager. They are internal B-trees.
        try
        {
            manage( btreeOfBtrees, INTERNAL_BTREE );

            currentBtreeOfBtreesOffset = ((PersistedBTree<NameRevision, Long>)btreeOfBtrees).getBtreeHeader().getBTreeHeaderOffset();
            updateRecordManagerHeader();

            // The FreePage B-tree
            manage( copiedPageBtree, INTERNAL_BTREE );

            currentCopiedPagesBtreeOffset = ((PersistedBTree<RevisionName, long[]>)copiedPageBtree).getBtreeHeader().getBTreeHeaderOffset();
            updateRecordManagerHeader();
        }
        catch ( BTreeAlreadyManagedException btame )
        {
            // Can't happen here.
        }

        // We are all set !
    }


    /**
     * Create the B-treeOfBtrees
     */
    private void createBtreeOfBtrees()
    {
        PersistedBTreeConfiguration<NameRevision, Long> configuration = new PersistedBTreeConfiguration<NameRevision, Long>();
        configuration.setKeySerializer( NameRevisionSerializer.INSTANCE );
        configuration.setName( BTREE_OF_BTREES_NAME );
        configuration.setValueSerializer( LongSerializer.INSTANCE );
        configuration.setBtreeType( BTreeTypeEnum.BTREE_OF_BTREES );

        btreeOfBtrees = BTreeFactory.createPersistedBTree( configuration );
    }


    /**
     * Create the CopiedPagesBtree
     */
    private void createCopiedPagesBtree()
    {
        PersistedBTreeConfiguration<RevisionName, long[]> configuration = new PersistedBTreeConfiguration<RevisionName, long[]>();
        configuration.setKeySerializer( RevisionNameSerializer.INSTANCE );
        configuration.setName( COPIED_PAGE_BTREE_NAME );
        configuration.setValueSerializer( LongArraySerializer.INSTANCE );
        configuration.setBtreeType( BTreeTypeEnum.COPIED_PAGES_BTREE );

        copiedPageBtree = BTreeFactory.createPersistedBTree( configuration );
    }


    /**
     * Load the BTrees from the disk.
     *
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws ClassNotFoundException
     * @throws NoSuchFieldException
     * @throws SecurityException
     * @throws IllegalArgumentException
     */
    private void loadRecordManager() throws IOException, ClassNotFoundException, IllegalAccessException,
        InstantiationException, IllegalArgumentException, SecurityException, NoSuchFieldException
    {
        if ( fileChannel.size() != 0 )
        {
            ByteBuffer recordManagerHeader = ByteBuffer.allocate( RECORD_MANAGER_HEADER_SIZE );

            // The file exists, we have to load the data now
            fileChannel.read( recordManagerHeader );

            recordManagerHeader.rewind();

            // read the RecordManager Header :
            // +---------------------+
            // | PageSize            | 4 bytes : The size of a physical page (default to 4096)
            // +---------------------+
            // | NbTree              | 4 bytes : The number of managed B-trees (at least 1)
            // +---------------------+
            // | FirstFree           | 8 bytes : The offset of the first free page
            // +---------------------+
            // | current BoB offset  | 8 bytes : The offset of the current B-tree of B-trees
            // +---------------------+
            // | previous BoB offset | 8 bytes : The offset of the previous B-tree of B-trees
            // +---------------------+
            // | current CP offset   | 8 bytes : The offset of the current Copied Pages B-tree
            // +---------------------+
            // | previous CP offset  | 8 bytes : The offset of the previous Copied Pages B-tree
            // +---------------------+

            // The page size
            pageSize = recordManagerHeader.getInt();

            // The number of managed B-trees
            nbBtree = recordManagerHeader.getInt();

            // The first and last free page
            firstFreePage = recordManagerHeader.getLong();

            // The current BOB offset
            currentBtreeOfBtreesOffset = recordManagerHeader.getLong();

            // The previous BOB offset
            previousBtreeOfBtreesOffset = recordManagerHeader.getLong();

            // The current Copied Pages B-tree offset
            currentCopiedPagesBtreeOffset = recordManagerHeader.getLong();

            // The previous Copied Pages B-tree offset
            previousCopiedPagesBtreeOffset = recordManagerHeader.getLong();

            // read the B-tree of B-trees
            PageIO[] bobHeaderPageIos = readPageIOs( currentBtreeOfBtreesOffset, Long.MAX_VALUE );

            btreeOfBtrees = BTreeFactory.<NameRevision, Long> createPersistedBTree( BTreeTypeEnum.BTREE_OF_BTREES );
            //BTreeFactory.<NameRevision, Long> setBtreeHeaderOffset( ( PersistedBTree<NameRevision, Long> )btreeOfBtrees, currentBtreeOfBtreesOffset );

            loadBtree( bobHeaderPageIos, btreeOfBtrees );

            // read the copied page B-tree
            PageIO[] copiedPagesPageIos = readPageIOs( currentCopiedPagesBtreeOffset, Long.MAX_VALUE );

            copiedPageBtree = BTreeFactory.<RevisionName, long[]> createPersistedBTree( BTreeTypeEnum.COPIED_PAGES_BTREE );
            //( ( PersistedBTree<RevisionName, long[]> ) copiedPageBtree ).setBtreeHeaderOffset( currentCopiedPagesBtreeOffset );

            loadBtree( copiedPagesPageIos, copiedPageBtree );

            // Now, read all the B-trees from the btree of btrees
            TupleCursor<NameRevision, Long> btreeCursor = btreeOfBtrees.browse();
            Map<String, Long> loadedBtrees = new HashMap<String, Long>();

            // loop on all the btrees we have, and keep only the latest revision
            long currentRevision = -1L;

            while ( btreeCursor.hasNext() )
            {
                Tuple<NameRevision, Long> btreeTuple = btreeCursor.next();
                NameRevision nameRevision = btreeTuple.getKey();
                long btreeOffset = btreeTuple.getValue();
                long revision = nameRevision.getValue();

                // Check if we already have processed this B-tree
                Long loadedBtreeRevision = loadedBtrees.get( nameRevision.getName() );

                if ( loadedBtreeRevision != null )
                {
                    // The btree has already been loaded. The revision is necessarily higher
                    if ( revision > currentRevision )
                    {
                        // We have a newer revision : switch to the new revision (we keep the offset atm)
                        loadedBtrees.put( nameRevision.getName(), btreeOffset );
                        currentRevision = revision;
                    }
                }
                else
                {
                    // This is a new B-tree
                    loadedBtrees.put( nameRevision.getName(), btreeOffset );
                    currentRevision = nameRevision.getRevision();
                }
            }

            // TODO : clean up the old revisions...


            // Now, we can load the real btrees using the offsets
            for ( String btreeName : loadedBtrees.keySet() )
            {
                long btreeOffset = loadedBtrees.get( btreeName );

                PageIO[] btreePageIos = readPageIOs( btreeOffset, Long.MAX_VALUE );

                BTree<?, ?> btree = BTreeFactory.<NameRevision, Long> createPersistedBTree();
                //( ( PersistedBTree<NameRevision, Long> ) btree ).setBtreeHeaderOffset( btreeOffset );
                loadBtree( btreePageIos, btree );

                // Add the btree into the map of managed B-trees
                managedBtrees.put( btreeName, ( BTree<Object, Object> ) btree );
            }

            // We are done ! Let's finish with the last initialization parts
            endOfFileOffset = fileChannel.size();
        }
    }


    /**
     * Starts a transaction
     */
    /*No Qualifier*/void beginTransaction()
    {
        transactionLock.lock();

        lockHeldCounter++;
    }


    /**
     * Commits a transaction
     */
    public void commit()
    {
        lockHeldCounter--;

        if ( !fileChannel.isOpen() )
        {
            // The file has been closed, nothing remains to commit, let's get out
            transactionLock.unlock();
            return;
        }

        if ( lockHeldCounter == 0 )
        {
            // We are done with the transaction
            // First update the RMHeader to be sure that we have a way to restore from a crash
            updateRecordManagerHeader();

            // We can now free pages
            for ( PageIO pageIo : freedPages )
            {
                try
                {
                    free( pageIo );
                }
                catch ( IOException ioe )
                {
                    throw new RecordManagerException( ioe.getMessage() );
                }
            }

            // Release the allocated and freed pages list
            freedPages.clear();
            allocatedPages.clear();

            // And update the RMHeader again, removing the old references to BOB and CPB b-tree headers
            // here, we have to erase the old references to keep only the new ones.
            updateRecordManagerHeader();
        }

        transactionLock.unlock();
    }


    /**
     * Rollback a transaction
     */
    public void rollback()
    {
        lockHeldCounter--;

        if ( lockHeldCounter == 0 )
        {
            // We can now free allocated pages, this is the end of the transaction
            for ( PageIO pageIo : allocatedPages )
            {
                try
                {
                    free( pageIo );
                }
                catch ( IOException ioe )
                {
                    throw new RecordManagerException( ioe.getMessage() );
                }
            }

            // Release the allocated and freed pages list
            freedPages.clear();
            allocatedPages.clear();

            // And update the RMHeader
            updateRecordManagerHeader();
        }

        transactionLock.unlock();
    }


    /**
     * Reads all the PageIOs that are linked to the page at the given position, including
     * the first page.
     *
     * @param position The position of the first page
     * @param limit The maximum bytes to read. Set this value to -1 when the size is unknown.
     * @return An array of pages
     */
    /*no qualifier*/ PageIO[] readPageIOs( long position, long limit ) throws IOException, EndOfFileExceededException
    {
        LOG.debug( "Read PageIOs at position {}", position );

        if ( limit <= 0 )
        {
            limit = Long.MAX_VALUE;
        }

        PageIO firstPage = fetchPage( position );
        firstPage.setSize();
        List<PageIO> listPages = new ArrayList<PageIO>();
        listPages.add( firstPage );
        long dataRead = pageSize - LONG_SIZE - INT_SIZE;

        // Iterate on the pages, if needed
        long nextPage = firstPage.getNextPage();

        if ( ( dataRead < limit ) && ( nextPage != NO_PAGE ) )
        {
            while ( dataRead < limit )
            {
                PageIO page = fetchPage( nextPage );
                listPages.add( page );
                nextPage = page.getNextPage();
                dataRead += pageSize - LONG_SIZE;

                if ( nextPage == NO_PAGE )
                {
                    page.setNextPage( NO_PAGE );
                    break;
                }
            }
        }

        LOG.debug( "Nb of PageIOs read : {}", listPages.size() );

        // Return
        return listPages.toArray( new PageIO[]
            {} );
    }


    /**
     * Check the offset to be sure it's a valid one :
     * <ul>
     * <li>It's >= 0</li>
     * <li>It's below the end of the file</li>
     * <li>It's a multipl of the pageSize
     * </ul>
     * @param offset The offset to check
     * @throws InvalidOffsetException If the offset is not valid
     */
    private void checkOffset( long offset )
    {
        if ( ( offset < 0 ) || ( offset > endOfFileOffset ) || ( ( offset % pageSize ) != 0 ) )
        {
            throw new InvalidOffsetException( "Bad Offset : " + offset );
        }
    }


    /**
     * Read a B-tree from the disk. The meta-data are at the given position in the list of pages.
     * We load a B-tree in two steps : first, we load the B-tree header, then the common informations
     *
     * @param pageIos The list of pages containing the meta-data
     * @param btree The B-tree we have to initialize
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws ClassNotFoundException
     * @throws NoSuchFieldException
     * @throws SecurityException
     * @throws IllegalArgumentException
     */
    private <K, V> void loadBtree( PageIO[] pageIos, BTree<K, V> btree ) throws EndOfFileExceededException,
        IOException, ClassNotFoundException, IllegalAccessException, InstantiationException, IllegalArgumentException, SecurityException, NoSuchFieldException
    {
        long dataPos = 0L;

        // Process the B-tree header
        BTreeHeader<K, V> btreeHeader = new BTreeHeader<K, V>();
        btreeHeader.setBtree( btree );

        // The BtreeHeader offset
        btreeHeader.setBTreeHeaderOffset( pageIos[0].getOffset() );

        // The B-tree current revision
        long revision = readLong( pageIos, dataPos );
        btreeHeader.setRevision( revision );
        dataPos += LONG_SIZE;

        // The nb elems in the tree
        long nbElems = readLong( pageIos, dataPos );
        btreeHeader.setNbElems( nbElems );
        dataPos += LONG_SIZE;

        // The B-tree rootPage offset
        long rootPageOffset = readLong( pageIos, dataPos );
        btreeHeader.setRootPageOffset( rootPageOffset );
        dataPos += LONG_SIZE;

        // The B-tree information offset
        long btreeInfoOffset = readLong( pageIos, dataPos );

        // Now, process the common informations
        PageIO[] infoPageIos = readPageIOs( btreeInfoOffset, Long.MAX_VALUE );
        ((PersistedBTree<K, V>)btree).setBtreeInfoOffset( infoPageIos[0].getOffset() );
        dataPos = 0L;

        // The B-tree page size
        int btreePageSize = readInt( infoPageIos, dataPos );
        BTreeFactory.setPageSize( btree, btreePageSize );
        dataPos += INT_SIZE;

        // The tree name
        ByteBuffer btreeNameBytes = readBytes( infoPageIos, dataPos );
        dataPos += INT_SIZE + btreeNameBytes.limit();
        String btreeName = Strings.utf8ToString( btreeNameBytes );
        BTreeFactory.setName( btree, btreeName );

        // The keySerializer FQCN
        ByteBuffer keySerializerBytes = readBytes( infoPageIos, dataPos );
        dataPos += INT_SIZE + keySerializerBytes.limit();

        String keySerializerFqcn = "";

        if ( keySerializerBytes != null )
        {
            keySerializerFqcn = Strings.utf8ToString( keySerializerBytes );
        }

        BTreeFactory.setKeySerializer( btree, keySerializerFqcn );

        // The valueSerialier FQCN
        ByteBuffer valueSerializerBytes = readBytes( infoPageIos, dataPos );

        String valueSerializerFqcn = "";
        dataPos += INT_SIZE + valueSerializerBytes.limit();

        if ( valueSerializerBytes != null )
        {
            valueSerializerFqcn = Strings.utf8ToString( valueSerializerBytes );
        }

        BTreeFactory.setValueSerializer( btree, valueSerializerFqcn );

        // The B-tree allowDuplicates flag
        int allowDuplicates = readInt( infoPageIos, dataPos );
        ( ( PersistedBTree<K, V> ) btree ).setAllowDuplicates( allowDuplicates != 0 );
        dataPos += INT_SIZE;

        // Set the current revision to the one stored in the B-tree header
        ((PersistedBTree<K, V>)btree).storeRevision( btreeHeader );

        // Now, init the B-tree
        ( ( PersistedBTree<K, V> ) btree ).setRecordManager( this );
        ( ( PersistedBTree<K, V> ) btree ).init();

        // Read the rootPage pages on disk
        PageIO[] rootPageIos = readPageIOs( rootPageOffset, Long.MAX_VALUE );

        Page<K, V> btreeRoot = readPage( btree, rootPageIos );
        BTreeFactory.setRecordManager( btree, this );

        BTreeFactory.setRootPage( btree, btreeRoot );
    }


    /**
     * Deserialize a Page from a B-tree at a give position
     *
     * @param btree The B-tree we want to read a Page from
     * @param offset The position in the file for this page
     * @return The read page
     * @throws EndOfFileExceededException If we have reached the end of the file while reading the page
     */
    public <K, V> Page<K, V> deserialize( BTree<K, V> btree, long offset ) throws EndOfFileExceededException,
        IOException
    {
        checkOffset( offset );
        PageIO[] rootPageIos = readPageIOs( offset, Long.MAX_VALUE );

        Page<K, V> page = readPage( btree, rootPageIos );

        ( ( AbstractPage<K, V> ) page ).setOffset( rootPageIos[0].getOffset() );
        ( ( AbstractPage<K, V> ) page ).setLastOffset( rootPageIos[rootPageIos.length - 1].getOffset() );

        return page;
    }


    /**
     * Read a page from some PageIO for a given B-tree
     * @param btree The B-tree we want to read a page for
     * @param pageIos The PageIO containing the raw data
     * @return The read Page if successful
     * @throws IOException If the deserialization failed
     */
    private <K, V> Page<K, V> readPage( BTree<K, V> btree, PageIO[] pageIos ) throws IOException
    {
        // Deserialize the rootPage now
        long position = 0L;

        // The revision
        long revision = readLong( pageIos, position );
        position += LONG_SIZE;

        // The number of elements in the page
        int nbElems = readInt( pageIos, position );
        position += INT_SIZE;

        // The size of the data containing the keys and values
        Page<K, V> page = null;

        // Reads the bytes containing all the keys and values, if we have some
        // We read  big blog of data into  ByteBuffer, then we will process
        // this ByteBuffer
        ByteBuffer byteBuffer = readBytes( pageIos, position );

        // Now, deserialize the data block. If the number of elements
        // is positive, it's a Leaf, otherwise it's a Node
        // Note that only a leaf can have 0 elements, and it's the root page then.
        if ( nbElems >= 0 )
        {
            // It's a leaf
            page = readLeafKeysAndValues( btree, nbElems, revision, byteBuffer, pageIos );
        }
        else
        {
            // It's a node
            page = readNodeKeysAndValues( btree, -nbElems, revision, byteBuffer, pageIos );
        }

        return page;
    }


    /**
     * Deserialize a Leaf from some PageIOs
     */
    private <K, V> PersistedLeaf<K, V> readLeafKeysAndValues( BTree<K, V> btree, int nbElems, long revision,
        ByteBuffer byteBuffer, PageIO[] pageIos )
    {
        // Its a leaf, create it
        PersistedLeaf<K, V> leaf = ( PersistedLeaf<K, V> ) BTreeFactory.createLeaf( btree, revision, nbElems );

        // Store the page offset on disk
        leaf.setOffset( pageIos[0].getOffset() );
        leaf.setLastOffset( pageIos[pageIos.length - 1].getOffset() );

        int[] keyLengths = new int[nbElems];
        int[] valueLengths = new int[nbElems];

        // Read each key and value
        for ( int i = 0; i < nbElems; i++ )
        {
            // Read the number of values
            int nbValues = byteBuffer.getInt();
            PersistedValueHolder<V> valueHolder = null;

            if ( nbValues < 0 )
            {
                // This is a sub-btree
                byte[] btreeOffsetBytes = new byte[LONG_SIZE];
                byteBuffer.get( btreeOffsetBytes );

                // Create the valueHolder. As the number of values is negative, we have to switch
                // to a positive value but as we start at -1 for 0 value, add 1.
                valueHolder = new PersistedValueHolder<V>( btree, 1 - nbValues, btreeOffsetBytes );
            }
            else
            {
                // This is an array
                // Read the value's array length
                valueLengths[i] = byteBuffer.getInt();

                // This is an Array of values, read the byte[] associated with it
                byte[] arrayBytes = new byte[valueLengths[i]];
                byteBuffer.get( arrayBytes );
                valueHolder = new PersistedValueHolder<V>( btree, nbValues, arrayBytes );
            }

            BTreeFactory.setValue( btree, leaf, i, valueHolder );

            keyLengths[i] = byteBuffer.getInt();
            byte[] data = new byte[keyLengths[i]];
            byteBuffer.get( data );
            BTreeFactory.setKey( btree, leaf, i, data );
        }

        return leaf;
    }


    /**
     * Deserialize a Node from some PageIos
     */
    private <K, V> PersistedNode<K, V> readNodeKeysAndValues( BTree<K, V> btree, int nbElems, long revision,
        ByteBuffer byteBuffer, PageIO[] pageIos ) throws IOException
    {
        PersistedNode<K, V> node = ( PersistedNode<K, V> ) BTreeFactory.createNode( btree, revision, nbElems );

        // Read each value and key
        for ( int i = 0; i < nbElems; i++ )
        {
            // This is an Offset
            long offset = LongSerializer.INSTANCE.deserialize( byteBuffer );
            long lastOffset = LongSerializer.INSTANCE.deserialize( byteBuffer );

            PersistedPageHolder<K, V> valueHolder = new PersistedPageHolder<K, V>( btree, null, offset, lastOffset );
            node.setValue( i, valueHolder );

            // Read the key length
            int keyLength = byteBuffer.getInt();

            int currentPosition = byteBuffer.position();

            // and the key value
            K key = btree.getKeySerializer().deserialize( byteBuffer );

            // Set the new position now
            byteBuffer.position( currentPosition + keyLength );

            BTreeFactory.setKey( btree, node, i, key );
        }

        // and read the last value, as it's a node
        long offset = LongSerializer.INSTANCE.deserialize( byteBuffer );
        long lastOffset = LongSerializer.INSTANCE.deserialize( byteBuffer );

        PersistedPageHolder<K, V> valueHolder = new PersistedPageHolder<K, V>( btree, null, offset, lastOffset );
        node.setValue( nbElems, valueHolder );

        return node;
    }


    /**
     * Read a byte[] from pages.
     *
     * @param pageIos The pages we want to read the byte[] from
     * @param position The position in the data stored in those pages
     * @return The byte[] we have read
     */
    private ByteBuffer readBytes( PageIO[] pageIos, long position )
    {
        // Read the byte[] length first
        int length = readInt( pageIos, position );
        position += INT_SIZE;

        // Compute the page in which we will store the data given the
        // current position
        int pageNb = computePageNb( position );

        // Compute the position in the current page
        int pagePos = ( int ) ( position + ( pageNb + 1 ) * LONG_SIZE + INT_SIZE ) - pageNb * pageSize;

        ByteBuffer pageData = pageIos[pageNb].getData();
        int remaining = pageData.capacity() - pagePos;

        if ( length == 0 )
        {
            // No bytes to read : return null;
            return null;
        }
        else
        {
            ByteBuffer bytes = ByteBuffer.allocate( length );

            while ( length > 0 )
            {
                if ( length <= remaining )
                {
                    pageData.mark();
                    pageData.position( pagePos );
                    int oldLimit = pageData.limit();
                    pageData.limit( pagePos + length );
                    bytes.put( pageData );
                    pageData.limit( oldLimit );
                    pageData.reset();
                    bytes.rewind();

                    return bytes;
                }

                pageData.mark();
                pageData.position( pagePos );
                int oldLimit = pageData.limit();
                pageData.limit( pagePos + remaining );
                bytes.put( pageData );
                pageData.limit( oldLimit );
                pageData.reset();
                pageNb++;
                pagePos = LINK_SIZE;
                pageData = pageIos[pageNb].getData();
                length -= remaining;
                remaining = pageData.capacity() - pagePos;
            }

            bytes.rewind();

            return bytes;
        }
    }


    /**
     * Read an int from pages
     * @param pageIos The pages we want to read the int from
     * @param position The position in the data stored in those pages
     * @return The int we have read
     */
    private int readInt( PageIO[] pageIos, long position )
    {
        // Compute the page in which we will store the data given the
        // current position
        int pageNb = computePageNb( position );

        // Compute the position in the current page
        int pagePos = ( int ) ( position + ( pageNb + 1 ) * LONG_SIZE + INT_SIZE ) - pageNb * pageSize;

        ByteBuffer pageData = pageIos[pageNb].getData();
        int remaining = pageData.capacity() - pagePos;
        int value = 0;

        if ( remaining >= INT_SIZE )
        {
            value = pageData.getInt( pagePos );
        }
        else
        {
            value = 0;

            switch ( remaining )
            {
                case 3:
                    value += ( ( pageData.get( pagePos + 2 ) & 0x00FF ) << 8 );
                    // Fallthrough !!!

                case 2:
                    value += ( ( pageData.get( pagePos + 1 ) & 0x00FF ) << 16 );
                    // Fallthrough !!!

                case 1:
                    value += ( pageData.get( pagePos ) << 24 );
                    break;
            }

            // Now deal with the next page
            pageData = pageIos[pageNb + 1].getData();
            pagePos = LINK_SIZE;

            switch ( remaining )
            {
                case 1:
                    value += ( pageData.get( pagePos ) & 0x00FF ) << 16;
                    // fallthrough !!!

                case 2:
                    value += ( pageData.get( pagePos + 2 - remaining ) & 0x00FF ) << 8;
                    // fallthrough !!!

                case 3:
                    value += ( pageData.get( pagePos + 3 - remaining ) & 0x00FF );
                    break;
            }
        }

        return value;
    }


    /**
     * Read a byte from pages
     * @param pageIos The pages we want to read the byte from
     * @param position The position in the data stored in those pages
     * @return The byte we have read
     */
    private byte readByte( PageIO[] pageIos, long position )
    {
        // Compute the page in which we will store the data given the
        // current position
        int pageNb = computePageNb( position );

        // Compute the position in the current page
        int pagePos = ( int ) ( position + ( pageNb + 1 ) * LONG_SIZE + INT_SIZE ) - pageNb * pageSize;

        ByteBuffer pageData = pageIos[pageNb].getData();
        byte value = 0;

        value = pageData.get( pagePos );

        return value;
    }


    /**
     * Read a long from pages
     * @param pageIos The pages we want to read the long from
     * @param position The position in the data stored in those pages
     * @return The long we have read
     */
    private long readLong( PageIO[] pageIos, long position )
    {
        // Compute the page in which we will store the data given the
        // current position
        int pageNb = computePageNb( position );

        // Compute the position in the current page
        int pagePos = ( int ) ( position + ( pageNb + 1 ) * LONG_SIZE + INT_SIZE ) - pageNb * pageSize;

        ByteBuffer pageData = pageIos[pageNb].getData();
        int remaining = pageData.capacity() - pagePos;
        long value = 0L;

        if ( remaining >= LONG_SIZE )
        {
            value = pageData.getLong( pagePos );
        }
        else
        {
            switch ( remaining )
            {
                case 7:
                    value += ( ( ( long ) pageData.get( pagePos + 6 ) & 0x00FF ) << 8 );
                    // Fallthrough !!!

                case 6:
                    value += ( ( ( long ) pageData.get( pagePos + 5 ) & 0x00FF ) << 16 );
                    // Fallthrough !!!

                case 5:
                    value += ( ( ( long ) pageData.get( pagePos + 4 ) & 0x00FF ) << 24 );
                    // Fallthrough !!!

                case 4:
                    value += ( ( ( long ) pageData.get( pagePos + 3 ) & 0x00FF ) << 32 );
                    // Fallthrough !!!

                case 3:
                    value += ( ( ( long ) pageData.get( pagePos + 2 ) & 0x00FF ) << 40 );
                    // Fallthrough !!!

                case 2:
                    value += ( ( ( long ) pageData.get( pagePos + 1 ) & 0x00FF ) << 48 );
                    // Fallthrough !!!

                case 1:
                    value += ( ( long ) pageData.get( pagePos ) << 56 );
                    break;
            }

            // Now deal with the next page
            pageData = pageIos[pageNb + 1].getData();
            pagePos = LINK_SIZE;

            switch ( remaining )
            {
                case 1:
                    value += ( ( long ) pageData.get( pagePos ) & 0x00FF ) << 48;
                    // fallthrough !!!

                case 2:
                    value += ( ( long ) pageData.get( pagePos + 2 - remaining ) & 0x00FF ) << 40;
                    // fallthrough !!!

                case 3:
                    value += ( ( long ) pageData.get( pagePos + 3 - remaining ) & 0x00FF ) << 32;
                    // fallthrough !!!

                case 4:
                    value += ( ( long ) pageData.get( pagePos + 4 - remaining ) & 0x00FF ) << 24;
                    // fallthrough !!!

                case 5:
                    value += ( ( long ) pageData.get( pagePos + 5 - remaining ) & 0x00FF ) << 16;
                    // fallthrough !!!

                case 6:
                    value += ( ( long ) pageData.get( pagePos + 6 - remaining ) & 0x00FF ) << 8;
                    // fallthrough !!!

                case 7:
                    value += ( ( long ) pageData.get( pagePos + 7 - remaining ) & 0x00FF );
                    break;
            }
        }

        return value;
    }


    /**
     * Manage a B-tree. The btree will be added and managed by this RecordManager. We will create a
     * new RootPage for this added B-tree, which will contain no data.<br/>
     * This method is threadsafe.
     *
     * @param btree The new B-tree to manage.
     * @throws BTreeAlreadyManagedException if the B-tree is already managed
     * @throws IOException if there was a problem while accessing the file
     */
    public synchronized <K, V> void manage( BTree<K, V> btree ) throws BTreeAlreadyManagedException, IOException
    {
        beginTransaction();

        manage( ( BTree<Object, Object> ) btree, NORMAL_BTREE );

        commit();
    }


    /**
     * Managing a btree is a matter of storing an reference to the managed B-tree in the B-tree Of B-trees.
     * We store a tuple of NameRevision (where revision is 0L) and a offset to the B-tree header.
     * At the same time, we keep a track of the managed B-trees in a Map.
     *
     * @param btree The new B-tree to manage.
     * @param treeType flag indicating if this is an internal tree
     *
     * @throws BTreeAlreadyManagedException If the B-tree is already managed
     * @throws IOException
     */
    public synchronized <K, V> void manage( BTree<K, V> btree, boolean treeType )
        throws BTreeAlreadyManagedException, IOException
    {
        LOG.debug( "Managing the btree {} which is an internam tree : {}", btree.getName(), treeType );
        BTreeFactory.setRecordManager( btree, this );

        String name = btree.getName();

        if ( managedBtrees.containsKey( name ) )
        {
            // There is already a B-tree with this name in the recordManager...
            LOG.error( "There is already a B-tree named '{}' managed by this recordManager", name );
            throw new BTreeAlreadyManagedException( name );
        }

        // Now, write the B-tree informations
        long btreeInfoOffset = writeBtreeInfo( btree );
        BTreeHeader<K, V> btreeHeader = ((AbstractBTree<K,V>)btree).getBtreeHeader();
        ((PersistedBTree<K, V>)btree).setBtreeInfoOffset( btreeInfoOffset );

        // Serialize the B-tree root page
        Page<K, V> rootPage = btreeHeader.getRootPage();

        PageIO[] rootPageIos = serializePage( btree, btreeHeader.getRevision(), rootPage );

        // Get the reference on the first page
        long rootPageOffset =  rootPageIos[0].getOffset();

        // Store the rootPageOffset into the Btree header and into the rootPage
        btreeHeader.setRootPageOffset( rootPageOffset );
        ( ( PersistedLeaf<K, V> ) rootPage ).setOffset( rootPageOffset );

        LOG.debug( "Flushing the newly managed '{}' btree rootpage", btree.getName() );
        flushPages( rootPageIos );

        // And the B-tree header
        long btreeHeaderOffset = writeBtreeHeader( btree, btreeHeader );

        // Now, if this is a new B-tree, add it to the B-tree of B-trees
        if ( treeType != INTERNAL_BTREE )
        {
            // Add the btree into the map of managed B-trees
            managedBtrees.put( name, ( BTree<Object, Object> ) btree );

            // We can safely increment the number of managed B-trees
            nbBtree++;

            // Create the new NameRevision
            NameRevision nameRevision = new NameRevision( name, 0L );

            // Inject it into the B-tree of B-tree
            btreeOfBtrees.insert( nameRevision, btreeHeaderOffset );
        }

        if ( LOG_CHECK.isDebugEnabled() )
        {
            check();
        }
    }


    /**
     * Serialize a new Page. It will contain the following data :<br/>
     * <ul>
     * <li>the revision : a long</li>
     * <li>the number of elements : an int (if <= 0, it's a Node, otherwise it's a Leaf)</li>
     * <li>the size of the values/keys when serialized
     * <li>the keys : an array of serialized keys</li>
     * <li>the values : an array of references to the children pageIO offset (stored as long)
     * if it's a Node, or a list of values if it's a Leaf</li>
     * <li></li>
     * </ul>
     *
     * @param revision The node revision
     * @param keys The keys to serialize
     * @param children The references to the children
     * @return An array of pages containing the serialized node
     * @throws IOException
     */
    private <K, V> PageIO[] serializePage( BTree<K, V> btree, long revision, Page<K, V> page ) throws IOException
    {
        int nbElems = page.getNbElems();

        if ( nbElems == 0 )
        {
            return serializeRootPage( revision );
        }
        else
        {
            // Prepare a list of byte[] that will contain the serialized page
            int nbBuffers = 1 + 1 + 1 + nbElems * 3;
            int dataSize = 0;
            int serializedSize = 0;

            if ( page.isNode() )
            {
                // A Node has one more value to store
                nbBuffers++;
            }

            // Now, we can create the list with the right size
            List<byte[]> serializedData = new ArrayList<byte[]>( nbBuffers );

            // The revision
            byte[] buffer = LongSerializer.serialize( revision );
            serializedData.add( buffer );
            serializedSize += buffer.length;

            // The number of elements
            // Make it a negative value if it's a Node
            int pageNbElems = nbElems;

            if ( page.isNode() )
            {
                pageNbElems = -nbElems;
            }

            buffer = IntSerializer.serialize( pageNbElems );
            serializedData.add( buffer );
            serializedSize += buffer.length;

            // Iterate on the keys and values. We first serialize the value, then the key
            // until we are done with all of them. If we are serializing a page, we have
            // to serialize one more value
            for ( int pos = 0; pos < nbElems; pos++ )
            {
                // Start with the value
                if ( page.isNode() )
                {
                    dataSize += serializeNodeValue( ( PersistedNode<K, V> ) page, pos, serializedData );
                    dataSize += serializeNodeKey( ( PersistedNode<K, V> ) page, pos, serializedData );
                }
                else
                {
                    dataSize += serializeLeafValue( ( PersistedLeaf<K, V> ) page, pos, serializedData );
                    dataSize += serializeLeafKey( ( PersistedLeaf<K, V> ) page, pos, serializedData );
                }
            }

            // Nodes have one more value to serialize
            if ( page.isNode() )
            {
                dataSize += serializeNodeValue( ( PersistedNode<K, V> ) page, nbElems, serializedData );
            }

            // Store the data size
            buffer = IntSerializer.serialize( dataSize );
            serializedData.add( 2, buffer );
            serializedSize += buffer.length;

            serializedSize += dataSize;

            // We are done. Allocate the pages we need to store the data
            PageIO[] pageIos = getFreePageIOs( serializedSize );

            // And store the data into those pages
            long position = 0L;

            for ( byte[] bytes : serializedData )
            {
                position = storeRaw( position, bytes, pageIos );
            }

            return pageIos;
        }
    }


    /**
     * Serialize a Node's key
     */
    private <K, V> int serializeNodeKey( PersistedNode<K, V> node, int pos, List<byte[]> serializedData )
    {
        KeyHolder<K> holder = node.getKeyHolder( pos );
        byte[] buffer = ( ( PersistedKeyHolder<K> ) holder ).getRaw();

        // We have to store the serialized key length
        byte[] length = IntSerializer.serialize( buffer.length );
        serializedData.add( length );

        // And store the serialized key now if not null
        if ( buffer.length != 0 )
        {
            serializedData.add( buffer );
        }

        return buffer.length + INT_SIZE;
    }


    /**
     * Serialize a Node's Value. We store the two offsets of the child page.
     */
    private <K, V> int serializeNodeValue( PersistedNode<K, V> node, int pos, List<byte[]> serializedData )
        throws IOException
    {
        // For a node, we just store the children's offsets
        Page<K, V> child = node.getReference( pos );

        // The first offset
        byte[] buffer = LongSerializer.serialize( ( ( AbstractPage<K, V> ) child ).getOffset() );
        serializedData.add( buffer );
        int dataSize = buffer.length;

        // The last offset
        buffer = LongSerializer.serialize( ( ( AbstractPage<K, V> ) child ).getLastOffset() );
        serializedData.add( buffer );
        dataSize += buffer.length;

        return dataSize;
    }


    /**
     * Serialize a Leaf's key
     */
    private <K, V> int serializeLeafKey( PersistedLeaf<K, V> leaf, int pos, List<byte[]> serializedData )
    {
        int dataSize = 0;
        KeyHolder<K> keyHolder = leaf.getKeyHolder( pos );
        byte[] keyData = ( ( PersistedKeyHolder<K> ) keyHolder ).getRaw();

        if ( keyData != null )
        {
            // We have to store the serialized key length
            byte[] length = IntSerializer.serialize( keyData.length );
            serializedData.add( length );

            // And the key data
            serializedData.add( keyData );
            dataSize += keyData.length + INT_SIZE;
        }
        else
        {
            serializedData.add( IntSerializer.serialize( 0 ) );
            dataSize += INT_SIZE;
        }

        return dataSize;
    }


    /**
     * Serialize a Leaf's Value.
     */
    private <K, V> int serializeLeafValue( PersistedLeaf<K, V> leaf, int pos, List<byte[]> serializedData )
        throws IOException
    {
        // The value can be an Array or a sub-btree, but we don't care
        // we just iterate on all the values
        ValueHolder<V> valueHolder = leaf.getValue( pos );
        int dataSize = 0;
        int nbValues = valueHolder.size();

        if ( !valueHolder.isSubBtree() )
        {
            // Write the nb elements first
            byte[] buffer = IntSerializer.serialize( nbValues );
            serializedData.add( buffer );
            dataSize = INT_SIZE;

            // We have a serialized value. Just flush it
            byte[] data = ( ( PersistedValueHolder<V> ) valueHolder ).getRaw();
            dataSize += data.length;

            // Store the data size
            buffer = IntSerializer.serialize( data.length );
            serializedData.add( buffer );
            dataSize += INT_SIZE;

            // and add the data if it's not 0
            if ( data.length > 0 )
            {
                serializedData.add( data );
            }
        }
        else
        {
            if ( nbValues == 0 )
            {
                // No value.
                byte[] buffer = IntSerializer.serialize( nbValues );
                serializedData.add( buffer );

                return buffer.length;
            }

            if ( valueHolder.isSubBtree() )
            {
                // Store the nbVlues as a negative number. We add 1 so that 0 is not confused with an Array value
                byte[] buffer = IntSerializer.serialize( -( nbValues + 1 ) );
                serializedData.add( buffer );
                dataSize += buffer.length;

                // the B-tree offset
                buffer = LongSerializer.serialize( ( ( PersistedValueHolder<V> ) valueHolder ).getOffset() );
                serializedData.add( buffer );
                dataSize += buffer.length;
            }
            else
            {
                // This is an array, store the nb of values as a positive number
                byte[] buffer = IntSerializer.serialize( nbValues );
                serializedData.add( buffer );
                dataSize += buffer.length;

                // Now store each value
                byte[] data = ( ( PersistedValueHolder<V> ) valueHolder ).getRaw();
                buffer = IntSerializer.serialize( data.length );
                serializedData.add( buffer );
                dataSize += buffer.length;

                if ( data.length > 0 )
                {
                    serializedData.add( data );
                }

                dataSize += data.length;
            }
        }

        return dataSize;
    }


    /**
     * Write a root page with no elements in it
     */
    private PageIO[] serializeRootPage( long revision ) throws IOException
    {
        // We will have 1 single page if we have no elements
        PageIO[] pageIos = new PageIO[1];

        // This is either a new root page or a new page that will be filled later
        PageIO newPage = fetchNewPage();

        // We need first to create a byte[] that will contain all the data
        // For the root page, this is easy, as we only have to store the revision,
        // and the number of elements, which is 0.
        long position = 0L;

        position = store( position, revision, newPage );
        position = store( position, 0, newPage );

        // Update the page size now
        newPage.setSize( ( int ) position );

        // Insert the result into the array of PageIO
        pageIos[0] = newPage;

        return pageIos;
    }


    /**
     * Update the RecordManager header, injecting the following data :
     *
     * <pre>
     * +---------------------+
     * | PageSize            | 4 bytes : The size of a physical page (default to 4096)
     * +---------------------+
     * | NbTree              | 4 bytes : The number of managed B-trees (at least 1)
     * +---------------------+
     * | FirstFree           | 8 bytes : The offset of the first free page
     * +---------------------+
     * | current BoB offset  | 8 bytes : The offset of the current B-tree of B-trees
     * +---------------------+
     * | previous BoB offset | 8 bytes : The offset of the previous B-tree of B-trees
     * +---------------------+
     * | current CP offset   | 8 bytes : The offset of the current CopiedPages B-tree
     * +---------------------+
     * | previous CP offset  | 8 bytes : The offset of the previous CopiedPages B-tree
     * +---------------------+
     * </pre>
     */
    public void updateRecordManagerHeader()
    {
        // The page size
        int position = writeData( RECORD_MANAGER_HEADER_BYTES, 0, pageSize );

        // The number of managed B-tree
        position = writeData( RECORD_MANAGER_HEADER_BYTES, position, nbBtree );

        // The first free page
        position = writeData( RECORD_MANAGER_HEADER_BYTES, position, firstFreePage );

        // The offset of the current B-tree of B-trees
        position = writeData( RECORD_MANAGER_HEADER_BYTES, position, currentBtreeOfBtreesOffset );

        // The offset of the copied pages B-tree
        position = writeData( RECORD_MANAGER_HEADER_BYTES, position, previousBtreeOfBtreesOffset );

        // The offset of the current B-tree of B-trees
        position = writeData( RECORD_MANAGER_HEADER_BYTES, position, currentCopiedPagesBtreeOffset );

        // The offset of the copied pages B-tree
        position = writeData( RECORD_MANAGER_HEADER_BYTES, position, previousCopiedPagesBtreeOffset );

        // Write the RecordManager header on disk
        RECORD_MANAGER_HEADER_BUFFER.put( RECORD_MANAGER_HEADER_BYTES );
        RECORD_MANAGER_HEADER_BUFFER.flip();

        LOG.debug( "Update RM header" );

        if ( LOG_PAGES.isDebugEnabled() )
        {
            StringBuilder sb = new StringBuilder();

            sb.append( "First free page     : 0x" ).append( Long.toHexString( firstFreePage ) ).append( "\n" );
            sb.append( "Current BOB header  : 0x" ).append( Long.toHexString( currentBtreeOfBtreesOffset ) ).append( "\n" );
            sb.append( "Previous BOB header : 0x" ).append( Long.toHexString( previousBtreeOfBtreesOffset ) ).append( "\n" );
            sb.append( "Current CPB header  : 0x" ).append( Long.toHexString( currentCopiedPagesBtreeOffset ) ).append( "\n" );
            sb.append( "Previous CPB header : 0x" ).append( Long.toHexString( previousCopiedPagesBtreeOffset ) ).append( "\n" );

            if ( firstFreePage != NO_PAGE )
            {
                long freePage = firstFreePage;
                sb.append( "free pages list : " );

                boolean isFirst = true;

                while ( freePage != NO_PAGE )
                {
                    if ( isFirst )
                    {
                        isFirst = false;
                    }
                    else
                    {
                        sb.append( " -> " );
                    }

                    sb.append( "0x" ).append( Long.toHexString( freePage ) );

                    try
                    {
                        PageIO[] freePageIO = readPageIOs( freePage, 8 );

                        freePage = freePageIO[0].getNextPage();
                    }
                    catch ( EndOfFileExceededException e )
                    {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                    catch ( IOException e )
                    {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }

            }

            LOG_PAGES.debug( "Update RM Header : \n{}", sb.toString() );
        }

        try
        {
            fileChannel.write( RECORD_MANAGER_HEADER_BUFFER, 0 );
        }
        catch ( IOException ioe )
        {
            throw new FileException( ioe.getMessage() );
        }

        RECORD_MANAGER_HEADER_BUFFER.clear();

        // Reset the old versions
        previousBtreeOfBtreesOffset = -1L;
        previousCopiedPagesBtreeOffset = -1L;

        nbUpdateRMHeader.incrementAndGet();
    }


    /**
     * Update the RecordManager header, injecting the following data :
     *
     * <pre>
     * +---------------------+
     * | PageSize            | 4 bytes : The size of a physical page (default to 4096)
     * +---------------------+
     * | NbTree              | 4 bytes : The number of managed B-trees (at least 1)
     * +---------------------+
     * | FirstFree           | 8 bytes : The offset of the first free page
     * +---------------------+
     * | current BoB offset  | 8 bytes : The offset of the current B-tree of B-trees
     * +---------------------+
     * | previous BoB offset | 8 bytes : The offset of the previous B-tree of B-trees
     * +---------------------+
     * | current CP offset   | 8 bytes : The offset of the current CopiedPages B-tree
     * +---------------------+
     * | previous CP offset  | 8 bytes : The offset of the previous CopiedPages B-tree
     * +---------------------+
     * </pre>
     */
    public void updateRecordManagerHeader( long newBtreeOfBtreesOffset, long newCopiedPageBtreeOffset )
    {
        if ( newBtreeOfBtreesOffset != -1L )
        {
            previousBtreeOfBtreesOffset = currentBtreeOfBtreesOffset;
            currentBtreeOfBtreesOffset = newBtreeOfBtreesOffset;
        }

        if ( newCopiedPageBtreeOffset != -1L )
        {
            previousCopiedPagesBtreeOffset = currentCopiedPagesBtreeOffset;
            currentCopiedPagesBtreeOffset = newCopiedPageBtreeOffset;
        }
    }


    /**
     * Inject an int into a byte[] at a given position.
     */
    private int writeData( byte[] buffer, int position, int value )
    {
        RECORD_MANAGER_HEADER_BYTES[position] = ( byte ) ( value >>> 24 );
        RECORD_MANAGER_HEADER_BYTES[position+1] = ( byte ) ( value >>> 16 );
        RECORD_MANAGER_HEADER_BYTES[position+2] = ( byte ) ( value >>> 8 );
        RECORD_MANAGER_HEADER_BYTES[position+3] = ( byte ) ( value );

        return position + 4;
    }


    /**
     * Inject a long into a byte[] at a given position.
     */
    private int writeData( byte[] buffer, int position, long value )
    {
        RECORD_MANAGER_HEADER_BYTES[position] = ( byte ) ( value >>> 56 );
        RECORD_MANAGER_HEADER_BYTES[position+1] = ( byte ) ( value >>> 48 );
        RECORD_MANAGER_HEADER_BYTES[position+2] = ( byte ) ( value >>> 40 );
        RECORD_MANAGER_HEADER_BYTES[position+3] = ( byte ) ( value >>> 32 );
        RECORD_MANAGER_HEADER_BYTES[position+4] = ( byte ) ( value >>> 24 );
        RECORD_MANAGER_HEADER_BYTES[position+5] = ( byte ) ( value >>> 16 );
        RECORD_MANAGER_HEADER_BYTES[position+6] = ( byte ) ( value >>> 8 );
        RECORD_MANAGER_HEADER_BYTES[position+7] = ( byte ) ( value );

        return position + 8;
    }


    /**
     * Add a new <btree, revision> tuple into the B-tree of B-trees.
     *
     * @param name The B-tree name
     * @param revision The B-tree revision
     * @param btreeHeaderOffset The B-tree offset
     * @throws IOException If the update failed
     */
    /* no qualifier */ <K, V> void addInBtreeOfBtrees( String name, long revision, long btreeHeaderOffset ) throws IOException
    {
        checkOffset( btreeHeaderOffset );
        NameRevision nameRevision = new NameRevision( name, revision );

        btreeOfBtrees.insert( nameRevision, btreeHeaderOffset );

        // Update the B-tree of B-trees
        currentBtreeOfBtreesOffset = ((AbstractBTree<K, V>)btreeOfBtrees).getBtreeHeader().getBTreeHeaderOffset();
    }


    /**
     * Add a new <btree, revision> tuple into the CopiedPages B-tree.
     *
     * @param name The B-tree name
     * @param revision The B-tree revision
     * @param btreeHeaderOffset The B-tree offset
     * @throws IOException If the update failed
     */
    /* no qualifier */ <K, V> void addInCopiedPagesBtree( String name, long revision, List<Page<K, V>> pages ) throws IOException
    {
        RevisionName revisionName = new RevisionName( revision, name );

        long[] pageOffsets = new long[pages.size()];
        int pos = 0;

        for ( Page<K, V> page : pages )
        {
            pageOffsets[pos++] = ((AbstractPage<K, V>)page).getOffset();
        }

        copiedPageBtree.insert( revisionName, pageOffsets );

        // Update the B-tree of B-trees
        currentCopiedPagesBtreeOffset = ((AbstractBTree<K, V>)copiedPageBtree).getBtreeHeader().getBTreeHeaderOffset();
    }


    /**
     * Internal method used to update the B-tree of B-trees offset
     * @param btreeOfBtreesOffset The new offset
     */
    /* no qualifier */ void setBtreeOfBtreesOffset( long btreeOfBtreesOffset )
    {
        checkOffset( btreeOfBtreesOffset );
        this.currentBtreeOfBtreesOffset = btreeOfBtreesOffset;
    }


    /**
     * Write the B-tree header on disk. We will write the following informations :
     * <pre>
     * +------------+
     * | revision   | The B-tree revision
     * +------------+
     * | nbElems    | The B-tree number of elements
     * +------------+
     * | rootPage   | The root page offset
     * +------------+
     * | BtreeInfo  | The B-tree info offset
     * +------------+
     * </pre>
     * @param btree The B-tree which header has to be written
     * @param btreeInfoOffset The offset of the B-tree informations
     * @return The B-tree header offset
     * @throws IOException If we weren't able to write the B-tree header
     */
    /* no qualifier */ <K, V> long writeBtreeHeader( BTree<K, V> btree, BTreeHeader<K, V> btreeHeader ) throws IOException
    {
        int bufferSize =
            LONG_SIZE +                     // The revision
            LONG_SIZE +                     // the number of element
            LONG_SIZE +                     // The root page offset
            LONG_SIZE;                      // The B-tree info page offset

        // Get the pageIOs we need to store the data. We may need more than one.
        PageIO[] btreeHeaderPageIos = getFreePageIOs( bufferSize );

        // Store the B-tree header Offset into the B-tree
        long btreeHeaderOffset = btreeHeaderPageIos[0].getOffset();

        // Now store the B-tree data in the pages :
        // - the B-tree revision
        // - the B-tree number of elements
        // - the B-tree root page offset
        // - the B-tree info page offset
        // Starts at 0
        long position = 0L;

        // The B-tree current revision
        position = store( position, btreeHeader.getRevision(), btreeHeaderPageIos );

        // The nb elems in the tree
        position = store( position, btreeHeader.getNbElems(), btreeHeaderPageIos );


        // Now, we can inject the B-tree rootPage offset into the B-tree header
        position = store( position, btreeHeader.getRootPageOffset(), btreeHeaderPageIos );

        // The B-tree info page offset
        position = store( position, ((PersistedBTree<K, V>)btree).getBtreeInfoOffset(), btreeHeaderPageIos );

        // And flush the pages to disk now
        LOG.debug( "Flushing the newly managed '{}' btree header", btree.getName() );

        if ( LOG_PAGES.isDebugEnabled() )
        {
            LOG_PAGES.debug( "Writing BTreeHeader revision {} for {}", btreeHeader.getRevision(), btree.getName() );
            StringBuilder sb = new StringBuilder();

            sb.append( "Offset : " ).append( Long.toHexString( btreeHeaderOffset ) ).append( "\n" );
            sb.append( "    Revision : " ).append( btreeHeader.getRevision() ).append( "\n" );
            sb.append( "    NbElems  : " ).append( btreeHeader.getNbElems() ).append( "\n" );
            sb.append( "    RootPage : 0x" ).append( Long.toHexString( btreeHeader.getRootPageOffset() ) ).append( "\n" );
            sb.append( "    Info     : 0x" ).append( Long.toHexString( ((PersistedBTree<K, V>)btree).getBtreeInfoOffset() ) ).append( "\n" );

            LOG_PAGES.debug( "Btree Header[{}]\n{}", btreeHeader.getRevision(), sb.toString() );
        }

        flushPages( btreeHeaderPageIos );

        btreeHeader.setBTreeHeaderOffset( btreeHeaderOffset );

        return btreeHeaderOffset;
    }


    /**
     * Write the B-tree informations on disk. We will write the following informations :
     * <pre>
     * +------------+
     * | pageSize   | The B-tree page size (ie, the number of elements per page max)
     * +------------+
     * | nameSize   | The B-tree name size
     * +------------+
     * | name       | The B-tree name
     * +------------+
     * | keySerSize | The keySerializer FQCN size
     * +------------+
     * | keySerFQCN | The keySerializer FQCN
     * +------------+
     * | valSerSize | The Value serializer FQCN size
     * +------------+
     * | valSerKQCN | The valueSerializer FQCN
     * +------------+
     * | dups       | The flags that tell if the dups are allowed
     * +------------+
     * </pre>
     * @param btree The B-tree which header has to be written
     * @return The B-tree header offset
     * @throws IOException If we weren't able to write the B-tree header
     */
    private <K, V> long writeBtreeInfo( BTree<K, V> btree ) throws IOException
    {
        // We will add the newly managed B-tree at the end of the header.
        byte[] btreeNameBytes = Strings.getBytesUtf8( btree.getName() );
        byte[] keySerializerBytes = Strings.getBytesUtf8( btree.getKeySerializerFQCN() );
        byte[] valueSerializerBytes = Strings.getBytesUtf8( btree.getValueSerializerFQCN() );

        int bufferSize =
            INT_SIZE +                      // The page size
            INT_SIZE +                      // The name size
            btreeNameBytes.length +         // The name
            INT_SIZE +                      // The keySerializerBytes size
            keySerializerBytes.length +     // The keySerializerBytes
            INT_SIZE +                      // The valueSerializerBytes size
            valueSerializerBytes.length +   // The valueSerializerBytes
            INT_SIZE;                       // The allowDuplicates flag

        // Get the pageIOs we need to store the data. We may need more than one.
        PageIO[] btreeHeaderPageIos = getFreePageIOs( bufferSize );

        // Keep the B-tree header Offset into the B-tree
        long btreeInfoOffset = btreeHeaderPageIos[0].getOffset();

        // Now store the B-tree information data in the pages :
        // - the B-tree page size
        // - the B-tree name
        // - the keySerializer FQCN
        // - the valueSerializer FQCN
        // - the flags that tell if the dups are allowed
        // Starts at 0
        long position = 0L;

        // The B-tree page size
        position = store( position, btree.getPageSize(), btreeHeaderPageIos );

        // The tree name
        position = store( position, btreeNameBytes, btreeHeaderPageIos );

        // The keySerializer FQCN
        position = store( position, keySerializerBytes, btreeHeaderPageIos );

        // The valueSerialier FQCN
        position = store( position, valueSerializerBytes, btreeHeaderPageIos );

        // The allowDuplicates flag
        position = store( position, ( btree.isAllowDuplicates() ? 1 : 0 ), btreeHeaderPageIos );

        // And flush the pages to disk now
        LOG.debug( "Flushing the newly managed '{}' btree header", btree.getName() );
        flushPages( btreeHeaderPageIos );

        return btreeInfoOffset;
    }


    /**
     * Update the B-tree header after a B-tree modification. This will make the latest modification
     * visible.<br/>
     * We update the following fields :
     * <ul>
     * <li>the revision</li>
     * <li>the number of elements</li>
     * <li>the B-tree root page offset</li>
     * </ul>
     * <br/>
     * As a result, a new version of the BtreHeader will be created, which will replace the previous
     * B-tree header
     * @param btree TheB-tree to update
     * @param btreeHeaderOffset The offset of the modified btree header
     * @return The offset of the new B-tree Header
     * @throws IOException If we weren't able to write the file on disk
     * @throws EndOfFileExceededException If we tried to write after the end of the file
     */
    /* no qualifier */ <K, V> long updateBtreeHeader( BTree<K, V> btree, long btreeHeaderOffset )
        throws EndOfFileExceededException, IOException
    {
        return updateBtreeHeader( btree, btreeHeaderOffset, false );
    }


    /**
     * Update the B-tree header after a B-tree modification. This will make the latest modification
     * visible.<br/>
     * We update the following fields :
     * <ul>
     * <li>the revision</li>
     * <li>the number of elements</li>
     * <li>the reference to the current B-tree revisions</li>
     * <li>the reference to the old B-tree revisions</li>
     * </ul>
     * <br/>
     * As a result, we new version of the BtreHeader will be created
     * @param btree The B-tree to update
     * @param btreeHeaderOffset The offset of the modified btree header
     * @return The offset of the new B-tree Header if it has changed (ie, when the onPlace flag is set to true)
     * @throws IOException
     * @throws EndOfFileExceededException
     */
    /* no qualifier */ <K, V> void updateBtreeHeaderOnPlace( BTree<K, V> btree, long btreeHeaderOffset )
        throws EndOfFileExceededException,
        IOException
    {
        updateBtreeHeader( btree, btreeHeaderOffset, true );
    }


    /**
     * Update the B-tree header after a B-tree modification. This will make the latest modification
     * visible.<br/>
     * We update the following fields :
     * <ul>
     * <li>the revision</li>
     * <li>the number of elements</li>
     * <li>the reference to the current B-tree revisions</li>
     * <li>the reference to the old B-tree revisions</li>
     * </ul>
     * <br/>
     * As a result, a new version of the BtreHeader will be created, which may replace the previous
     * B-tree header (if the onPlace flag is set to true) or a new set of pageIos will contain the new
     * version.
     *
     * @param btree The B-tree to update
     * @param rootPageOffset The offset of the modified rootPage
     * @param onPlace Tells if we modify the B-tree on place, or if we create a copy
     * @return The offset of the new B-tree Header if it has changed (ie, when the onPlace flag is set to true)
     * @throws EndOfFileExceededException If we tried to write after the end of the file
     * @throws IOException If tehre were some error while writing the data on disk
     */
    private <K, V> long updateBtreeHeader( BTree<K, V> btree, long btreeHeaderOffset, boolean onPlace )
        throws EndOfFileExceededException, IOException
    {
        // Read the pageIOs associated with this B-tree
        PageIO[] pageIos;
        long newBtreeHeaderOffset = NO_PAGE;
        long offset = ( ( PersistedBTree<K, V> ) btree ).getBtreeOffset();

        if ( onPlace )
        {
            // We just have to update the existing BTreeHeader
            long headerSize = LONG_SIZE + LONG_SIZE + LONG_SIZE;

            pageIos = readPageIOs( offset, headerSize );

            // Now, update the revision
            long position = 0;

            position = store( position, btree.getRevision(), pageIos );
            position = store( position, btree.getNbElems(), pageIos );
            position = store( position, btreeHeaderOffset, pageIos );

            // Write the pages on disk
            if ( LOG.isDebugEnabled() )
            {
                LOG.debug( "-----> Flushing the '{}' B-treeHeader", btree.getName() );
                LOG.debug( "  revision : " + btree.getRevision() + ", NbElems : " + btree.getNbElems() + ", btreeHeader offset : 0x"
                    + Long.toHexString( btreeHeaderOffset ) );
            }

            // Get new place on disk to store the modified BTreeHeader if it's not onPlace
            // Rewrite the pages at the same place
            LOG.debug( "Rewriting the B-treeHeader on place for B-tree " + btree.getName() );
            flushPages( pageIos );
        }
        else
        {
            // We have to read and copy the existing BTreeHeader and to create a new one
            pageIos = readPageIOs( offset, Long.MAX_VALUE );

            // Now, copy every read page
            PageIO[] newPageIOs = new PageIO[pageIos.length];
            int pos = 0;

            for ( PageIO pageIo : pageIos )
            {
                // Fetch a free page
                newPageIOs[pos] = fetchNewPage();

                // keep a track of the allocated and copied pages so that we can
                // free them when we do a commit or rollback, if the btree is an management one
                if ( ( btree.getType() == BTreeTypeEnum.BTREE_OF_BTREES ) || ( btree.getType() == BTreeTypeEnum.COPIED_PAGES_BTREE ) )
                {
                    freedPages.add( pageIo );
                    allocatedPages.add( newPageIOs[pos] );
                }

                pageIo.copy( newPageIOs[pos] );

                if ( pos > 0 )
                {
                    newPageIOs[pos - 1].setNextPage( newPageIOs[pos].getOffset() );
                }

                pos++;
            }

            // store the new btree header offset
            // and update the revision
            long position = 0;

            position = store( position, btree.getRevision(), newPageIOs );
            position = store( position, btree.getNbElems(), newPageIOs );
            position = store( position, btreeHeaderOffset, newPageIOs );

            // Get new place on disk to store the modified BTreeHeader if it's not onPlace
            // Flush the new B-treeHeader on disk
            LOG.debug( "Rewriting the B-treeHeader on place for B-tree " + btree.getName() );
            flushPages( newPageIOs );

            newBtreeHeaderOffset = newPageIOs[0].getOffset();
        }

        nbUpdateBtreeHeader.incrementAndGet();

        if ( LOG_CHECK.isDebugEnabled() )
        {
            check();
        }

        return newBtreeHeaderOffset;
    }


    /**
     * Write the pages on disk, either at the end of the file, or at
     * the position they were taken from.
     *
     * @param pageIos The list of pages to write
     * @throws IOException If the write failed
     */
    private void flushPages( PageIO... pageIos ) throws IOException
    {
        if ( LOG.isDebugEnabled() )
        {
            for ( PageIO pageIo : pageIos )
            {
                dump( pageIo );
            }
        }

        for ( PageIO pageIo : pageIos )
        {
            ByteBuffer data = pageIo.getData();
            pageIo.getData().rewind();

            if ( fileChannel.size() < ( pageIo.getOffset() + pageSize ) )
            {
                LOG.debug( "Adding a page at the end of the file" );
                // This is a page we have to add to the file
                fileChannel.write( pageIo.getData(), fileChannel.size() );
                //fileChannel.force( false );
            }
            else
            {
                LOG.debug( "Writing a page at position {}", pageIo.getOffset() );
                fileChannel.write( pageIo.getData(), pageIo.getOffset() );
                //fileChannel.force( false );
            }

            nbUpdatePageIOs.incrementAndGet();

            pageIo.getData().rewind();
        }
    }


    /**
     * Compute the page in which we will store data given an offset, when
     * we have a list of pages.
     *
     * @param offset The position in the data
     * @return The page number in which the offset will start
     */
    private int computePageNb( long offset )
    {
        long pageNb = 0;

        offset -= pageSize - LINK_SIZE - PAGE_SIZE;

        if ( offset < 0 )
        {
            return ( int ) pageNb;
        }

        pageNb = 1 + offset / ( pageSize - LINK_SIZE );

        return ( int ) pageNb;
    }


    /**
     * Stores a byte[] into one ore more pageIO (depending if the long is stored
     * across a boundary or not)
     *
     * @param position The position in a virtual byte[] if all the pages were contiguous
     * @param bytes The byte[] to serialize
     * @param pageIos The pageIOs we have to store the data in
     * @return The new offset
     */
    private long store( long position, byte[] bytes, PageIO... pageIos )
    {
        if ( bytes != null )
        {
            // Write the bytes length
            position = store( position, bytes.length, pageIos );

            // Compute the page in which we will store the data given the
            // current position
            int pageNb = computePageNb( position );

            // Get back the buffer in this page
            ByteBuffer pageData = pageIos[pageNb].getData();

            // Compute the position in the current page
            int pagePos = ( int ) ( position + ( pageNb + 1 ) * LONG_SIZE + INT_SIZE ) - pageNb * pageSize;

            // Compute the remaining size in the page
            int remaining = pageData.capacity() - pagePos;
            int nbStored = bytes.length;

            // And now, write the bytes until we have none
            while ( nbStored > 0 )
            {
                if ( remaining > nbStored )
                {
                    pageData.mark();
                    pageData.position( pagePos );
                    pageData.put( bytes, bytes.length - nbStored, nbStored );
                    pageData.reset();
                    nbStored = 0;
                }
                else
                {
                    pageData.mark();
                    pageData.position( pagePos );
                    pageData.put( bytes, bytes.length - nbStored, remaining );
                    pageData.reset();
                    pageNb++;
                    pageData = pageIos[pageNb].getData();
                    pagePos = LINK_SIZE;
                    nbStored -= remaining;
                    remaining = pageData.capacity() - pagePos;
                }
            }

            // We are done
            position += bytes.length;
        }
        else
        {
            // No bytes : write 0 and return
            position = store( position, 0, pageIos );
        }

        return position;
    }


    /**
     * Stores a byte[] into one ore more pageIO (depending if the long is stored
     * across a boundary or not). We don't add the byte[] size, it's already present
     * in the received byte[].
     *
     * @param position The position in a virtual byte[] if all the pages were contiguous
     * @param bytes The byte[] to serialize
     * @param pageIos The pageIOs we have to store the data in
     * @return The new offset
     */
    private long storeRaw( long position, byte[] bytes, PageIO... pageIos )
    {
        if ( bytes != null )
        {
            // Compute the page in which we will store the data given the
            // current position
            int pageNb = computePageNb( position );

            // Get back the buffer in this page
            ByteBuffer pageData = pageIos[pageNb].getData();

            // Compute the position in the current page
            int pagePos = ( int ) ( position + ( pageNb + 1 ) * LONG_SIZE + INT_SIZE ) - pageNb * pageSize;

            // Compute the remaining size in the page
            int remaining = pageData.capacity() - pagePos;
            int nbStored = bytes.length;

            // And now, write the bytes until we have none
            while ( nbStored > 0 )
            {
                if ( remaining > nbStored )
                {
                    pageData.mark();
                    pageData.position( pagePos );
                    pageData.put( bytes, bytes.length - nbStored, nbStored );
                    pageData.reset();
                    nbStored = 0;
                }
                else
                {
                    pageData.mark();
                    pageData.position( pagePos );
                    pageData.put( bytes, bytes.length - nbStored, remaining );
                    pageData.reset();
                    pageNb++;

                    if ( pageNb == pageIos.length )
                    {
                        // We can stop here : we have reach the end of the page
                        break;
                    }

                    pageData = pageIos[pageNb].getData();
                    pagePos = LINK_SIZE;
                    nbStored -= remaining;
                    remaining = pageData.capacity() - pagePos;
                }
            }

            // We are done
            position += bytes.length;
        }
        else
        {
            // No bytes : write 0 and return
            position = store( position, 0, pageIos );
        }

        return position;
    }


    /**
     * Stores an Integer into one ore more pageIO (depending if the int is stored
     * across a boundary or not)
     *
     * @param position The position in a virtual byte[] if all the pages were contiguous
     * @param value The int to serialize
     * @param pageIos The pageIOs we have to store the data in
     * @return The new offset
     */
    private long store( long position, int value, PageIO... pageIos )
    {
        // Compute the page in which we will store the data given the
        // current position
        int pageNb = computePageNb( position );

        // Compute the position in the current page
        int pagePos = ( int ) ( position + ( pageNb + 1 ) * LONG_SIZE + INT_SIZE ) - pageNb * pageSize;

        // Get back the buffer in this page
        ByteBuffer pageData = pageIos[pageNb].getData();

        // Compute the remaining size in the page
        int remaining = pageData.capacity() - pagePos;

        if ( remaining < INT_SIZE )
        {
            // We have to copy the serialized length on two pages

            switch ( remaining )
            {
                case 3:
                    pageData.put( pagePos + 2, ( byte ) ( value >>> 8 ) );
                    // Fallthrough !!!

                case 2:
                    pageData.put( pagePos + 1, ( byte ) ( value >>> 16 ) );
                    // Fallthrough !!!

                case 1:
                    pageData.put( pagePos, ( byte ) ( value >>> 24 ) );
                    break;
            }

            // Now deal with the next page
            pageData = pageIos[pageNb + 1].getData();
            pagePos = LINK_SIZE;

            switch ( remaining )
            {
                case 1:
                    pageData.put( pagePos, ( byte ) ( value >>> 16 ) );
                    // fallthrough !!!

                case 2:
                    pageData.put( pagePos + 2 - remaining, ( byte ) ( value >>> 8 ) );
                    // fallthrough !!!

                case 3:
                    pageData.put( pagePos + 3 - remaining, ( byte ) ( value ) );
                    break;
            }
        }
        else
        {
            // Store the value in the page at the selected position
            pageData.putInt( pagePos, value );
        }

        // Increment the position to reflect the addition of an Int (4 bytes)
        position += INT_SIZE;

        return position;
    }


    /**
     * Stores a Long into one ore more pageIO (depending if the long is stored
     * across a boundary or not)
     *
     * @param position The position in a virtual byte[] if all the pages were contiguous
     * @param value The long to serialize
     * @param pageIos The pageIOs we have to store the data in
     * @return The new offset
     */
    private long store( long position, long value, PageIO... pageIos )
    {
        // Compute the page in which we will store the data given the
        // current position
        int pageNb = computePageNb( position );

        // Compute the position in the current page
        int pagePos = ( int ) ( position + ( pageNb + 1 ) * LONG_SIZE + INT_SIZE ) - pageNb * pageSize;

        // Get back the buffer in this page
        ByteBuffer pageData = pageIos[pageNb].getData();

        // Compute the remaining size in the page
        int remaining = pageData.capacity() - pagePos;

        if ( remaining < LONG_SIZE )
        {
            // We have to copy the serialized length on two pages

            switch ( remaining )
            {
                case 7:
                    pageData.put( pagePos + 6, ( byte ) ( value >>> 8 ) );
                    // Fallthrough !!!

                case 6:
                    pageData.put( pagePos + 5, ( byte ) ( value >>> 16 ) );
                    // Fallthrough !!!

                case 5:
                    pageData.put( pagePos + 4, ( byte ) ( value >>> 24 ) );
                    // Fallthrough !!!

                case 4:
                    pageData.put( pagePos + 3, ( byte ) ( value >>> 32 ) );
                    // Fallthrough !!!

                case 3:
                    pageData.put( pagePos + 2, ( byte ) ( value >>> 40 ) );
                    // Fallthrough !!!

                case 2:
                    pageData.put( pagePos + 1, ( byte ) ( value >>> 48 ) );
                    // Fallthrough !!!

                case 1:
                    pageData.put( pagePos, ( byte ) ( value >>> 56 ) );
                    break;
            }

            // Now deal with the next page
            pageData = pageIos[pageNb + 1].getData();
            pagePos = LINK_SIZE;

            switch ( remaining )
            {
                case 1:
                    pageData.put( pagePos, ( byte ) ( value >>> 48 ) );
                    // fallthrough !!!

                case 2:
                    pageData.put( pagePos + 2 - remaining, ( byte ) ( value >>> 40 ) );
                    // fallthrough !!!

                case 3:
                    pageData.put( pagePos + 3 - remaining, ( byte ) ( value >>> 32 ) );
                    // fallthrough !!!

                case 4:
                    pageData.put( pagePos + 4 - remaining, ( byte ) ( value >>> 24 ) );
                    // fallthrough !!!

                case 5:
                    pageData.put( pagePos + 5 - remaining, ( byte ) ( value >>> 16 ) );
                    // fallthrough !!!

                case 6:
                    pageData.put( pagePos + 6 - remaining, ( byte ) ( value >>> 8 ) );
                    // fallthrough !!!

                case 7:
                    pageData.put( pagePos + 7 - remaining, ( byte ) ( value ) );
                    break;
            }
        }
        else
        {
            // Store the value in the page at the selected position
            pageData.putLong( pagePos, value );
        }

        // Increment the position to reflect the addition of an Long (8 bytes)
        position += LONG_SIZE;

        return position;
    }


    /**
     * Write the page in a serialized form.
     *
     * @param btree The persistedBtree we will create a new PageHolder for
     * @param newPage The page to write on disk
     * @param newRevision The page's revision
     * @return A PageHolder containing the copied page
     * @throws IOException If the page can't be written on disk
     */
    /* No qualifier*/<K, V> PageHolder<K, V> writePage( BTree<K, V> btree, Page<K, V> newPage,
        long newRevision ) throws IOException
    {
        // We first need to save the new page on disk
        PageIO[] pageIos = serializePage( btree, newRevision, newPage );

        if ( LOG_PAGES.isDebugEnabled() )
        {
            LOG_PAGES.debug( "Write data for '{}' btree", btree.getName()  );

            logPageIos( pageIos );
        }

        // Write the page on disk
        flushPages( pageIos );

        // Build the resulting reference
        long offset = pageIos[0].getOffset();
        long lastOffset = pageIos[pageIos.length - 1].getOffset();
        PersistedPageHolder<K, V> pageHolder = new PersistedPageHolder<K, V>( btree, newPage, offset,
            lastOffset );

        if ( LOG_CHECK.isDebugEnabled() )
        {
            check();
        }

        return pageHolder;
    }


    /* No qualifier */ static void logPageIos( PageIO[] pageIos )
    {
        int pageNb = 0;

        for ( PageIO pageIo : pageIos )
        {
            StringBuilder sb = new StringBuilder();
            sb.append( "PageIO[" ).append( pageNb ).append( "]:0x" );
            sb.append( Long.toHexString( pageIo.getOffset() ) ).append( "/");
            sb.append( pageIo.getSize() );
            pageNb++;

            ByteBuffer data = pageIo.getData();

            int position = data.position();
            byte[] bytes = new byte[(int)pageIo.getSize() + 12];

            data.get( bytes );
            data.position( position );
            int pos = 0;

            for ( byte b : bytes )
            {
                int mod = pos%16;

                switch ( mod )
                {
                    case 0:
                        sb.append( "\n    " );
                        // No break
                    case 4:
                    case 8:
                    case 12:
                        sb.append( " " );
                    case 1:
                    case 2:
                    case 3:
                    case 5:
                    case 6:
                    case 7:
                    case 9:
                    case 10:
                    case 11:
                    case 13:
                    case 14:
                    case 15:
                        sb.append( Strings.dumpByte( b ) ).append( " " );
                }
                pos++;
            }

            LOG_PAGES.debug( sb.toString() );
        }
    }


    /**
     * Compute the number of pages needed to store some specific size of data.
     *
     * @param dataSize The size of the data we want to store in pages
     * @return The number of pages needed
     */
    private int computeNbPages( int dataSize )
    {
        if ( dataSize <= 0 )
        {
            return 0;
        }

        // Compute the number of pages needed.
        // Considering that each page can contain PageSize bytes,
        // but that the first 8 bytes are used for links and we
        // use 4 bytes to store the data size, the number of needed
        // pages is :
        // NbPages = ( (dataSize - (PageSize - 8 - 4 )) / (PageSize - 8) ) + 1
        // NbPages += ( if (dataSize - (PageSize - 8 - 4 )) % (PageSize - 8) > 0 : 1 : 0 )
        int availableSize = ( pageSize - LONG_SIZE );
        int nbNeededPages = 1;

        // Compute the number of pages that will be full but the first page
        if ( dataSize > availableSize - INT_SIZE )
        {
            int remainingSize = dataSize - ( availableSize - INT_SIZE );
            nbNeededPages += remainingSize / availableSize;
            int remain = remainingSize % availableSize;

            if ( remain > 0 )
            {
                nbNeededPages++;
            }
        }

        return nbNeededPages;
    }


    /**
     * Get as many pages as needed to store the data of the given size. The returned
     * PageIOs are all linked together.
     *
     * @param dataSize The data size
     * @return An array of pages, enough to store the full data
     */
    private PageIO[] getFreePageIOs( int dataSize ) throws IOException
    {
        if ( dataSize == 0 )
        {
            return new PageIO[]
                {};
        }

        int nbNeededPages = computeNbPages( dataSize );

        PageIO[] pageIOs = new PageIO[nbNeededPages];

        // The first page : set the size
        pageIOs[0] = fetchNewPage();
        pageIOs[0].setSize( dataSize );

        for ( int i = 1; i < nbNeededPages; i++ )
        {
            pageIOs[i] = fetchNewPage();

            // Create the link
            pageIOs[i - 1].setNextPage( pageIOs[i].getOffset() );
        }

        return pageIOs;
    }


    /**
     * Return a new Page. We take one of the existing free pages, or we create
     * a new page at the end of the file.
     *
     * @return The fetched PageIO
     */
    private PageIO fetchNewPage() throws IOException
    {
        dumpFreePages( firstFreePage );

        if ( firstFreePage == NO_PAGE )
        {
            nbCreatedPages.incrementAndGet();

            // We don't have any free page. Reclaim some new page at the end
            // of the file
            PageIO newPage = new PageIO( endOfFileOffset );

            endOfFileOffset += pageSize;

            ByteBuffer data = ByteBuffer.allocateDirect( pageSize );

            newPage.setData( data );
            newPage.setNextPage( NO_PAGE );
            newPage.setSize( 0 );

            LOG.debug( "Requiring a new page at offset {}", newPage.getOffset() );

            return newPage;
        }
        else
        {
            nbReusedPages.incrementAndGet();

            // We have some existing free page. Fetch it from disk
            PageIO pageIo = fetchPage( firstFreePage );

            // Update the firstFreePage pointer
            firstFreePage = pageIo.getNextPage();

            // overwrite the data of old page
            ByteBuffer data = ByteBuffer.allocateDirect( pageSize );
            pageIo.setData( data );

            pageIo.setNextPage( NO_PAGE );
            pageIo.setSize( 0 );

            LOG.debug( "Reused page at offset {}", pageIo.getOffset() );

            return pageIo;
        }
    }


    /**
     * fetch a page from disk, knowing its position in the file.
     *
     * @param offset The position in the file
     * @return The found page
     */
    private PageIO fetchPage( long offset ) throws IOException, EndOfFileExceededException
    {
        checkOffset( offset );

        if ( fileChannel.size() < offset + pageSize )
        {
            // Error : we are past the end of the file
            throw new EndOfFileExceededException( "We are fetching a page on " + offset +
                " when the file's size is " + fileChannel.size() );
        }
        else
        {
            // Read the page
            fileChannel.position( offset );

            ByteBuffer data = ByteBuffer.allocate( pageSize );
            fileChannel.read( data );
            data.rewind();

            PageIO readPage = new PageIO( offset );
            readPage.setData( data );

            return readPage;
        }
    }


    /**
     * @return the pageSize
     */
    public int getPageSize()
    {
        return pageSize;
    }


    /**
     * Set the page size, ie the number of bytes a page can store.
     *
     * @param pageSize The number of bytes for a page
     */
    /* no qualifier */ void setPageSize( int pageSize )
    {
        if ( this.pageSize >= 13 )
        {
            this.pageSize = pageSize;
        }
        else
        {
            this.pageSize = DEFAULT_PAGE_SIZE;
        }
    }


    /**
     * Close the RecordManager and flush everything on disk
     */
    public void close() throws IOException
    {
        beginTransaction();

        // Close all the managed B-trees
        for ( BTree<Object, Object> tree : managedBtrees.values() )
        {
            tree.close();
        }

        // Close the management B-trees
        copiedPageBtree.close();
        btreeOfBtrees.close();

        managedBtrees.clear();

        // Write the data
        fileChannel.force( true );

        // And close the channel
        fileChannel.close();

        commit();
    }


    /** Hex chars */
    private static final byte[] HEX_CHAR = new byte[]
        { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };


    public static String dump( byte octet )
    {
        return new String( new byte[]
            { HEX_CHAR[( octet & 0x00F0 ) >> 4], HEX_CHAR[octet & 0x000F] } );
    }


    /**
     * Dump a pageIO
     */
    private void dump( PageIO pageIo )
    {
        ByteBuffer buffer = pageIo.getData();
        buffer.mark();
        byte[] longBuffer = new byte[LONG_SIZE];
        byte[] intBuffer = new byte[INT_SIZE];

        // get the next page offset
        buffer.get( longBuffer );
        long nextOffset = LongSerializer.deserialize( longBuffer );

        // Get the data size
        buffer.get( intBuffer );
        int size = IntSerializer.deserialize( intBuffer );

        buffer.reset();

        System.out.println( "PageIO[" + Long.toHexString( pageIo.getOffset() ) + "], size = " + size + ", NEXT PageIO:"
            + Long.toHexString( nextOffset ) );
        System.out.println( " 0  1  2  3  4  5  6  7  8  9  A  B  C  D  E  F " );
        System.out.println( "+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+" );

        for ( int i = 0; i < buffer.limit(); i += 16 )
        {
            System.out.print( "|" );

            for ( int j = 0; j < 16; j++ )
            {
                System.out.print( dump( buffer.get() ) );

                if ( j == 15 )
                {
                    System.out.println( "|" );
                }
                else
                {
                    System.out.print( " " );
                }
            }
        }

        System.out.println( "+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+" );

        buffer.reset();
    }


    /**
     * Dump the RecordManager file
     * @throws IOException
     */
    public void dump()
    {
        LOG.debug( "/---------------------------- Dump ----------------------------\\" );
//        System.out.println( "/---------------------------- Dump ----------------------------\\" );

        try
        {
            RandomAccessFile randomFile = new RandomAccessFile( file, "r" );
            FileChannel fileChannel = randomFile.getChannel();

            ByteBuffer recordManagerHeader = ByteBuffer.allocate( RECORD_MANAGER_HEADER_SIZE );

            // load the RecordManager header
            fileChannel.read( recordManagerHeader );

            recordManagerHeader.rewind();

            // The page size
            long fileSize = fileChannel.size();
            int pageSize = recordManagerHeader.getInt();
            long nbPages = fileSize / pageSize;

            // The number of managed B-trees
            int nbBtree = recordManagerHeader.getInt();

            // The first free page
            long firstFreePage = recordManagerHeader.getLong();

            // The current B-tree of B-trees
            long currentBtreeOfBtreesPage = recordManagerHeader.getLong();

            // The previous B-tree of B-trees
            long previousBtreeOfBtreesPage = recordManagerHeader.getLong();

            // The current CopiedPages B-tree
            long currentCopiedPagesBtreePage = recordManagerHeader.getLong();

            // The previous CopiedPages B-tree
            long previousCopiedPagesBtreePage = recordManagerHeader.getLong();

            if ( LOG.isDebugEnabled() )
            {
                LOG.debug( "  RecordManager" );
                LOG.debug( "  -------------" );
                LOG.debug( "    Header " );
                LOG.debug( "    Size = 0x{}", Long.toHexString( fileSize ) );
                LOG.debug( "    NbPages = {}", nbPages );
                LOG.debug( "      page size : {}", pageSize );
                LOG.debug( "      nbTree : {}", nbBtree );
                LOG.debug( "      firstFreePage : {}", Long.toHexString( firstFreePage ) );
                LOG.debug( "      current BOB : {}", Long.toHexString( currentBtreeOfBtreesPage ) );
                LOG.debug( "      previous BOB : {}", Long.toHexString( previousBtreeOfBtreesPage ) );
                LOG.debug( "      current CopiedPages : {}", Long.toHexString( currentCopiedPagesBtreePage ) );
                LOG.debug( "      previous CopiedPages : {}", Long.toHexString( previousCopiedPagesBtreePage ) );
            }

//            System.out.println( "  RecordManager" );
//            System.out.println( "  -------------" );
//            System.out.println( "  Size = 0x" + Long.toHexString( fileSize ) );
//            System.out.println( "  NbPages = " + nbPages );
//            System.out.println( "    Header " );
//            System.out.println( "      page size : " + pageSize );
//            System.out.println( "      nbTree : " + nbBtree );
//            System.out.println( "      firstFreePage : 0x" + Long.toHexString( firstFreePage ) );
//            System.out.println( "      current BOB : 0x" + Long.toHexString( currentBtreeOfBtreesPage ) );
//            System.out.println( "      previous BOB : 0x" + Long.toHexString( previousBtreeOfBtreesPage ) );
//            System.out.println( "      current CopiedPages : 0x" + Long.toHexString( currentCopiedPagesBtreePage ) );
//            System.out.println( "      previous CopiedPages : 0x" + Long.toHexString( previousCopiedPagesBtreePage ) );

            long position = RECORD_MANAGER_HEADER_SIZE;

            // Dump the Free pages list
            dumpFreePages( firstFreePage );

            // Dump the B-tree of B-trees
            dumpBtreeHeader( currentBtreeOfBtreesPage );

            // Dump the previous B-tree of B-trees if any
            if ( previousBtreeOfBtreesPage != NO_PAGE )
            {
                dumpBtreeHeader( previousBtreeOfBtreesPage );
            }

            // Dump the CopiedPages B-tree
            dumpBtreeHeader( currentCopiedPagesBtreePage );


            // Dump the previous B-tree of B-trees if any
            if ( previousCopiedPagesBtreePage != NO_PAGE )
            {
                dumpBtreeHeader( previousCopiedPagesBtreePage );
            }

            // Dump all the user's B-tree
            randomFile.close();
            LOG.debug( "\\---------------------------- Dump ----------------------------/" );
//            System.out.println( "\\---------------------------- Dump ----------------------------/" );
        }
        catch ( IOException ioe )
        {
            System.out.println( "Exception while dumping the file : " + ioe.getMessage() );
        }
    }


    /**
     * Dump the free pages
     */
    private void dumpFreePages( long freePageOffset ) throws EndOfFileExceededException, IOException
    {
//        System.out.println( "\n  FreePages : " );
        LOG.debug( "\n  FreePages : " );
        int pageNb = 1;

        while ( freePageOffset != NO_PAGE )
        {
            PageIO pageIo = fetchPage( freePageOffset );

            LOG.debug( "    freePage[{}] : 0x{}", pageNb, Long.toHexString( pageIo.getOffset() ) );
//            System.out.println( "    freePage[" + pageNb + "] : 0x" + Long.toHexString( pageIo.getOffset() ) );

            freePageOffset = pageIo.getNextPage();
            pageNb++;
        }
    }


    /**
     * Dump a B-tree Header
     */
    private long dumpBtreeHeader( long btreeOffset ) throws EndOfFileExceededException, IOException
    {
        // First read the B-tree header
        PageIO[] pageIos = readPageIOs( btreeOffset, Long.MAX_VALUE );

        long dataPos = 0L;

        // The B-tree current revision
        long revision = readLong( pageIos, dataPos );
        dataPos += LONG_SIZE;

        // The nb elems in the tree
        long nbElems = readLong( pageIos, dataPos );
        dataPos += LONG_SIZE;

        // The B-tree rootPage offset
        long rootPageOffset = readLong( pageIos, dataPos );
        dataPos += LONG_SIZE;

        // The B-tree page size
        int btreePageSize = readInt( pageIos, dataPos );
        dataPos += INT_SIZE;

        // The tree name
        ByteBuffer btreeNameBytes = readBytes( pageIos, dataPos );
        dataPos += INT_SIZE + btreeNameBytes.limit();
        String btreeName = Strings.utf8ToString( btreeNameBytes );

        // The keySerializer FQCN
        ByteBuffer keySerializerBytes = readBytes( pageIos, dataPos );
        dataPos += INT_SIZE + keySerializerBytes.limit();

        String keySerializerFqcn = "";

        if ( keySerializerBytes != null )
        {
            keySerializerFqcn = Strings.utf8ToString( keySerializerBytes );
        }

        // The valueSerialier FQCN
        ByteBuffer valueSerializerBytes = readBytes( pageIos, dataPos );

        String valueSerializerFqcn = "";
        dataPos += INT_SIZE + valueSerializerBytes.limit();

        if ( valueSerializerBytes != null )
        {
            valueSerializerFqcn = Strings.utf8ToString( valueSerializerBytes );
        }

        // The B-tree allowDuplicates flag
        int allowDuplicates = readInt( pageIos, dataPos );
        boolean dupsAllowed = allowDuplicates != 0;

        dataPos += INT_SIZE;

//        System.out.println( "\n  B-Tree " + btreeName );
//        System.out.println( "  ------------------------- " );

//        System.out.println( "    nbPageIOs[" + pageIos.length + "] = " + pageIoList );
        if ( LOG.isDebugEnabled() )
        {
            StringBuilder sb = new StringBuilder();
            boolean isFirst = true;

            for ( PageIO pageIo : pageIos )
            {
                if ( isFirst )
                {
                    isFirst = false;
                }
                else
                {
                    sb.append( ", " );
                }

                sb.append( "0x" ).append( Long.toHexString( pageIo.getOffset() ) );
            }

            String pageIoList = sb.toString();

            LOG.debug( "    PageIOs[{}] = {}", pageIos.length, pageIoList );

//        System.out.println( "    dataSize = "+ pageIos[0].getSize() );
            LOG.debug( "    dataSize = {}", pageIos[0].getSize() );

            LOG.debug( "    B-tree '{}'", btreeName );
            LOG.debug( "    revision : {}", revision );
            LOG.debug( "    nbElems : {}", nbElems );
            LOG.debug( "    rootPageOffset : 0x{}", Long.toHexString( rootPageOffset ) );
            LOG.debug( "    B-tree page size : {}", btreePageSize );
            LOG.debug( "    keySerializer : '{}'", keySerializerFqcn );
            LOG.debug( "    valueSerializer : '{}'", valueSerializerFqcn );
            LOG.debug( "    dups allowed : {}", dupsAllowed );
//
//        System.out.println( "    B-tree '" + btreeName + "'" );
//        System.out.println( "    revision : " + revision );
//        System.out.println( "    nbElems : " + nbElems );
//        System.out.println( "    rootPageOffset : 0x" + Long.toHexString( rootPageOffset ) );
//        System.out.println( "    B-tree page size : " + btreePageSize );
//        System.out.println( "    keySerializer : " + keySerializerFqcn );
//        System.out.println( "    valueSerializer : " + valueSerializerFqcn );
//        System.out.println( "    dups allowed : " + dupsAllowed );
        }

        return rootPageOffset;
    }


    /**
     * Get the number of managed trees. We don't count the CopiedPage B-tree and the B-tree of B-trees
     *
     * @return The number of managed B-trees
     */
    public int getNbManagedTrees()
    {
        return nbBtree;
    }


    /**
     * Get the managed B-trees. We don't return the CopiedPage B-tree nor the B-tree of B-trees.
     *
     * @return The managed B-trees
     */
    public Set<String> getManagedTrees()
    {
        Set<String> btrees = new HashSet<String>( managedBtrees.keySet() );

        return btrees;
    }


    /**
     * Stores the copied pages into the CopiedPages B-tree
     *
     * @param name The B-tree name
     * @param revision The revision
     * @param copiedPages The pages that have been copied while creating this revision
     * @throws IOException If we weren't able to store the data on disk
     */
    /* No Qualifier */ void storeCopiedPages( String name, long revision, long[] copiedPages ) throws IOException
    {
        RevisionName revisionName = new RevisionName( revision, name );

        copiedPageBtree.insert( revisionName, copiedPages );
    }


    /**
     * Store a reference to an old rootPage into the Revision B-tree
     *
     * @param btree The B-tree we want to keep an old RootPage for
     * @param rootPage The old rootPage
     * @throws IOException If we have an issue while writing on disk
     */
    /* No qualifier */<K, V> void storeRootPage( BTree<K, V> btree, Page<K, V> rootPage ) throws IOException
    {
        if ( !isKeepRevisions() )
        {
            return;
        }

        if ( btree == copiedPageBtree )
        {
            return;
        }

        NameRevision nameRevision = new NameRevision( btree.getName(), rootPage.getRevision() );

        ( ( AbstractBTree<NameRevision, Long> ) btreeOfBtrees ).insert( nameRevision,
            ( ( AbstractPage<K, V> ) rootPage ).getOffset(), 0 );

        if ( LOG_CHECK.isDebugEnabled() )
        {
            check();
        }
    }


    /**
     * Fetch the rootPage of a given B-tree for a given revision.
     *
     * @param btree The B-tree we are interested in
     * @param revision The revision we want to get back
     * @return The rootPage for this B-tree and this revision, if any
     * @throws KeyNotFoundException If we can't find the rootPage for this revision and this B-tree
     * @throws IOException If we had an ise while accessing the data on disk
     */
    /* No qualifier */<K, V> Page<K, V> getRootPage( BTree<K, V> btree, long revision ) throws KeyNotFoundException,
        IOException
    {
        if ( btree.getRevision() == revision )
        {
            // We are asking for the current revision
            return btree.getRootPage();
        }

        // Get the B-tree header offset
        NameRevision nameRevision = new NameRevision( btree.getName(), revision );
        long btreeHeaderOffset = btreeOfBtrees.get( nameRevision );

        // get the B-tree rootPage
        Page<K, V> btreeRoot = readRootPage( btree, btreeHeaderOffset );

        return btreeRoot;
    }


    /**
     * Read a root page from the B-tree header offset
     */
    private <K, V> Page<K, V> readRootPage( BTree<K, V> btree, long btreeHeaderOffset ) throws EndOfFileExceededException, IOException
    {
        // Read the B-tree header pages on disk
        PageIO[] btreeHeaderPageIos = readPageIOs( btreeHeaderOffset, Long.MAX_VALUE );
        long dataPos = LONG_SIZE + LONG_SIZE;

        // The B-tree rootPage offset
        long rootPageOffset = readLong( btreeHeaderPageIos, dataPos );

        // Read the rootPage pages on disk
        PageIO[] rootPageIos = readPageIOs( rootPageOffset, Long.MAX_VALUE );

        // Now, convert it to a Page
        Page<K, V> btreeRoot = readPage( btree, rootPageIos );

        return btreeRoot;
    }


    /**
     * Get one managed trees, knowing its name.
     *
     * @param name The B-tree name we are looking for
     * @return The managed B-trees
     */
    public <K, V> BTree<K, V> getManagedTree( String name )
    {
        return ( BTree<K, V> ) managedBtrees.get( name );
    }


    /**
     * Move a list of pages to the free page list. A logical page is associated with one
     * or more physical PageIOs, which are on the disk. We have to move all those PagIO instances
     * to the free list, and do the same in memory (we try to keep a reference to a set of
     * free pages.
     *
     * @param btree The B-tree which were owning the pages
     * @param revision The current revision
     * @param pages The pages to free
     * @throws IOException If we had a problem while updating the file
     * @throws EndOfFileExceededException If we tried to write after the end of the file
     */
    /* Package protected */<K, V> void freePages( BTree<K, V> btree, long revision, List<Page<K, V>> pages )
        throws EndOfFileExceededException, IOException
    {
        if ( ( pages == null ) || pages.isEmpty() )
        {
            return;
        }

        if ( !keepRevisions )
        {
            // if the B-tree doesn't keep revisions, we can safely move
            // the pages to the freed page list.
            if ( LOG.isDebugEnabled() )
            {
                LOG.debug( "Freeing the following pages :" );

                for ( Page<K, V> page : pages )
                {
                    LOG.debug(  "    {}", page );
                }
            }

            for ( Page<K, V> page : pages )
            {
                long pageOffset = ((AbstractPage<K, V>)page).getOffset();

                PageIO[] pageIos = readPageIOs( pageOffset, Long.MAX_VALUE );

                for ( PageIO pageIo : pageIos )
                {
                    freedPages.add( pageIo );
                }
            }
        }
        else
        {
            // We are keeping revisions of standard B-trees, so we move the pages to the CopiedPages B-tree
            // but only for non managed B-trees
            if ( LOG.isDebugEnabled() )
            {
                LOG.debug( "Moving the following pages to the CopiedBtree :" );

                for ( Page<K, V> page : pages )
                {
                    LOG.debug(  "    {}", page );
                }
            }

            long[] pageOffsets = new long[pages.size()];
            int pos = 0;

            for ( Page<K, V> page : pages )
            {
                pageOffsets[pos++] = ((AbstractPage<K, V>)page).offset;
            }

            if ( ( btree.getType() != BTreeTypeEnum.BTREE_OF_BTREES ) && ( btree.getType() != BTreeTypeEnum.COPIED_PAGES_BTREE ) )
            {
                // Deal with standard B-trees
                RevisionName revisionName = new RevisionName( revision, btree.getName() );

                copiedPageBtree.insert( revisionName, pageOffsets );

                // Update the RecordManager Copiedpage Offset
                currentCopiedPagesBtreeOffset = ((PersistedBTree<RevisionName, long[]>)copiedPageBtree).getBtreeOffset();
            }
            else
            {
                // Managed B-trees : we simply free the copied pages
                for ( long pageOffset : pageOffsets )
                {
                    PageIO[] pageIos = readPageIOs( pageOffset, Long.MAX_VALUE );

                    for ( PageIO pageIo : pageIos )
                    {
                        freedPages.add( pageIo );
                    }
                }
            }
        }
    }


    /**
     * Add a PageIO to the list of free PageIOs
     *
     * @param pageIo The page to free
     * @throws IOException If we weren't capable of updating the file
     */
    private void free( PageIO pageIo ) throws IOException
    {
        // We add the Page's PageIOs before the
        // existing free pages.
        // Link it to the first free page
        pageIo.setNextPage( firstFreePage );

        LOG.debug( "Flushing the first free page" );

        // And flush it to disk
        flushPages( pageIo );

        // We can update the firstFreePage offset
        firstFreePage = pageIo.getOffset();
    }


    /**
     * @return the keepRevisions flag
     */
    public boolean isKeepRevisions()
    {
        return keepRevisions;
    }


    /**
     * @param keepRevisions the keepRevisions flag to set
     */
    public void setKeepRevisions( boolean keepRevisions )
    {
        this.keepRevisions = keepRevisions;
    }


    /**
     * Creates a B-tree and automatically adds it to the list of managed btrees
     *
     * @param name the name of the B-tree
     * @param keySerializer key serializer
     * @param valueSerializer value serializer
     * @param allowDuplicates flag for allowing duplicate keys
     * @return a managed B-tree
     * @throws IOException If we weren't able to update the file on disk
     * @throws BTreeAlreadyManagedException If the B-tree is already managed
     */
    @SuppressWarnings("all")
    public <K, V> BTree<K, V> addBTree( String name, ElementSerializer<K> keySerializer,
        ElementSerializer<V> valueSerializer, boolean allowDuplicates )
            throws IOException, BTreeAlreadyManagedException
    {
        PersistedBTreeConfiguration config = new PersistedBTreeConfiguration();

        config.setName( name );
        config.setKeySerializer( keySerializer );
        config.setValueSerializer( valueSerializer );
        config.setAllowDuplicates( allowDuplicates );

        BTree btree = new PersistedBTree( config );
        manage( btree );

        if ( LOG_CHECK.isDebugEnabled() )
        {
            check();
        }

        return btree;
    }


    private void setCheckedPage( long[] checkedPages, long offset, int pageSize )
    {
        long pageOffset = ( offset - RECORD_MANAGER_HEADER_SIZE ) / pageSize;
        int index = ( int ) ( pageOffset / 64L );
        long mask = ( 1L << ( pageOffset % 64L ) );
        long bits = checkedPages[index];

        if ( ( bits & mask ) == 1 )
        {
            throw new RecordManagerException( "The page at : " + offset + " has already been checked" );
        }

        checkedPages[index] |= mask;
    }


    /**
     * Check the free pages
     *
     * @param checkedPages
     * @throws IOException
     */
    private void checkFreePages( long[] checkedPages, int pageSize, long firstFreePage )
        throws IOException
    {
        if ( firstFreePage == NO_PAGE )
        {
            return;
        }

        // Now, read all the free pages
        long currentOffset = firstFreePage;
        long fileSize = fileChannel.size();

        while ( currentOffset != NO_PAGE )
        {
            if ( currentOffset > fileSize )
            {
                throw new FreePageException( "Wrong free page offset, above file size : " + currentOffset );
            }

            try
            {
                PageIO pageIo = fetchPage( currentOffset );

                if ( currentOffset != pageIo.getOffset() )
                {
                    throw new InvalidBTreeException( "PageIO offset is incorrect : " + currentOffset + "-"
                        + pageIo.getOffset() );
                }

                setCheckedPage( checkedPages, currentOffset, pageSize );

                long newOffset = pageIo.getNextPage();
                currentOffset = newOffset;
            }
            catch ( IOException ioe )
            {
                throw new InvalidBTreeException( "Cannot fetch page at : " + currentOffset );
            }
        }
    }


    /**
     * Check the root page for a given B-tree
     * @throws IOException
     * @throws EndOfFileExceededException
     */
    private void checkRoot( long[] checkedPages, long offset, int pageSize, long nbBtreeElems,
        ElementSerializer keySerializer, ElementSerializer valueSerializer, boolean allowDuplicates )
        throws EndOfFileExceededException, IOException
    {
        // Read the rootPage pages on disk
        PageIO[] rootPageIos = readPageIOs( offset, Long.MAX_VALUE );

        // Deserialize the rootPage now
        long position = 0L;

        // The revision
        long revision = readLong( rootPageIos, position );
        position += LONG_SIZE;

        // The number of elements in the page
        int nbElems = readInt( rootPageIos, position );
        position += INT_SIZE;

        // The size of the data containing the keys and values
        ByteBuffer byteBuffer = null;

        // Reads the bytes containing all the keys and values, if we have some
        ByteBuffer data = readBytes( rootPageIos, position );

        if ( nbElems >= 0 )
        {
            // Its a leaf

            // Check the page offset
            long pageOffset = rootPageIos[0].getOffset();

            if ( ( pageOffset < 0 ) || ( pageOffset > fileChannel.size() ) )
            {
                throw new InvalidBTreeException( "The page offset is incorrect : " + pageOffset );
            }

            // Check the page last offset
            long pageLastOffset = rootPageIos[rootPageIos.length - 1].getOffset();

            if ( ( pageLastOffset <= 0 ) || ( pageLastOffset > fileChannel.size() ) )
            {
                throw new InvalidBTreeException( "The page last offset is incorrect : " + pageLastOffset );
            }

            // Read each value and key
            for ( int i = 0; i < nbElems; i++ )
            {
                // Just deserialize all the keys and values
                if ( allowDuplicates )
                {
                    /*
                    long value = OFFSET_SERIALIZER.deserialize( byteBuffer );

                    rootPageIos = readPageIOs( value, Long.MAX_VALUE );

                    BTree dupValueContainer = BTreeFactory.createBTree();
                    dupValueContainer.setBtreeOffset( value );

                    try
                    {
                        loadBTree( pageIos, dupValueContainer );
                    }
                    catch ( Exception e )
                    {
                        // should not happen
                        throw new InvalidBTreeException( e );
                    }
                    */
                }
                else
                {
                    valueSerializer.deserialize( byteBuffer );
                }

                keySerializer.deserialize( byteBuffer );
            }
        }
        else
        {
            /*
            // It's a node
            int nodeNbElems = -nbElems;

            // Read each value and key
            for ( int i = 0; i < nodeNbElems; i++ )
            {
                // This is an Offset
                long offset = OFFSET_SERIALIZER.deserialize( byteBuffer );
                long lastOffset = OFFSET_SERIALIZER.deserialize( byteBuffer );

                ElementHolder valueHolder = new ReferenceHolder( btree, null, offset, lastOffset );
                ( ( Node ) page ).setValue( i, valueHolder );

                Object key = btree.getKeySerializer().deserialize( byteBuffer );
                BTreeFactory.setKey( page, i, key );
            }

            // and read the last value, as it's a node
            long offset = OFFSET_SERIALIZER.deserialize( byteBuffer );
            long lastOffset = OFFSET_SERIALIZER.deserialize( byteBuffer );

            ElementHolder valueHolder = new ReferenceHolder( btree, null, offset, lastOffset );
            ( ( Node ) page ).setValue( nodeNbElems, valueHolder );*/
        }
    }


    /**
     * Check a B-tree
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws ClassNotFoundException
     */
    private long checkBtree( long[] checkedPages, PageIO[] pageIos, int pageSize, boolean isLast )
        throws EndOfFileExceededException, IOException, InstantiationException, IllegalAccessException,
        ClassNotFoundException
    {
        long dataPos = 0L;

        // The B-tree current revision
        long revision = readLong( pageIos, dataPos );
        dataPos += LONG_SIZE;

        // The nb elems in the tree
        long nbElems = readLong( pageIos, dataPos );
        dataPos += LONG_SIZE;

        // The B-tree rootPage offset
        long rootPageOffset = readLong( pageIos, dataPos );

        if ( ( rootPageOffset < 0 ) || ( rootPageOffset > fileChannel.size() ) )
        {
            throw new InvalidBTreeException( "The rootpage is incorrect : " + rootPageOffset );
        }

        dataPos += LONG_SIZE;

        // The next B-tree offset
        long nextBtreeOffset = readLong( pageIos, dataPos );

        if ( ( ( rootPageOffset < 0 ) && ( !isLast ) ) || ( nextBtreeOffset > fileChannel.size() ) )
        {
            throw new InvalidBTreeException( "The rootpage is incorrect : " + rootPageOffset );
        }

        dataPos += LONG_SIZE;

        // The B-tree page size
        int btreePageSize = readInt( pageIos, dataPos );

        if ( ( btreePageSize < 2 ) || ( ( btreePageSize & ( ~btreePageSize + 1 ) ) != btreePageSize ) )
        {
            throw new InvalidBTreeException( "The B-tree page size is not a power of 2 : " + btreePageSize );
        }

        dataPos += INT_SIZE;

        // The tree name
        ByteBuffer btreeNameBytes = readBytes( pageIos, dataPos );
        dataPos += INT_SIZE;

        dataPos += btreeNameBytes.limit();
        String btreeName = Strings.utf8ToString( btreeNameBytes );

        // The keySerializer FQCN
        ByteBuffer keySerializerBytes = readBytes( pageIos, dataPos );

        String keySerializerFqcn = null;
        dataPos += INT_SIZE;

        if ( keySerializerBytes != null )
        {
            dataPos += keySerializerBytes.limit();
            keySerializerFqcn = Strings.utf8ToString( keySerializerBytes );
        }
        else
        {
            keySerializerFqcn = "";
        }

        // The valueSerialier FQCN
        ByteBuffer valueSerializerBytes = readBytes( pageIos, dataPos );

        String valueSerializerFqcn = null;
        dataPos += INT_SIZE;

        if ( valueSerializerBytes != null )
        {
            dataPos += valueSerializerBytes.limit();
            valueSerializerFqcn = Strings.utf8ToString( valueSerializerBytes );
        }
        else
        {
            valueSerializerFqcn = "";
        }

        // The B-tree allowDuplicates flag
        int allowDuplicates = readInt( pageIos, dataPos );
        dataPos += INT_SIZE;

        // Now, check the rootPage, which can be a Leaf or a Node, depending
        // on the number of elements in the tree : if it's above the pageSize,
        // it's a Node, otherwise it's a Leaf
        Class<?> valueSerializer = Class.forName( valueSerializerFqcn );
        Class<?> keySerializer = Class.forName( keySerializerFqcn );

        /*
        checkRoot( checkedPages, rootPageOffset, pageSize, nbElems,
            ( ElementSerializer<?> ) keySerializer.newInstance(),
            ( ElementSerializer<?> ) valueSerializer.newInstance(), allowDuplicates != 0 );
        */

        return nextBtreeOffset;
    }


    /**
     * Check each B-tree we manage
     * @throws IOException
     * @throws EndOfFileExceededException
     * @throws ClassNotFoundException
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    private void checkBtrees( long[] checkedPages, int pageSize, int nbBtrees ) throws EndOfFileExceededException,
        IOException, InstantiationException, IllegalAccessException, ClassNotFoundException
    {
        // Iterate on each B-tree until we have exhausted all of them. The number
        // of btrees is just used to check that we have the correct number
        // of stored B-trees, as they are all linked.
        long position = RECORD_MANAGER_HEADER_SIZE;

        for ( int i = 0; i < nbBtrees; i++ )
        {
            // Load the pageIOs containing the B-tree
            PageIO[] pageIos = readPageIOs( position, Long.MAX_VALUE );

            // Check that they are correctly linked and not already used
            int pageNb = 0;

            for ( PageIO currentPageIo : pageIos )
            {
                //
                long nextPageOffset = currentPageIo.getNextPage();

                if ( pageNb == pageIos.length - 1 )
                {
                    if ( nextPageOffset != NO_PAGE )
                    {
                        throw new InvalidBTreeException( "The pointer to the next page is not valid, expected NO_PAGE" );
                    }
                }
                else
                {
                    if ( nextPageOffset == NO_PAGE )
                    {
                        throw new InvalidBTreeException( "The pointer to the next page is not valid, NO_PAGE" );
                    }
                }

                if ( ( nextPageOffset != NO_PAGE ) && ( ( nextPageOffset - RECORD_MANAGER_HEADER_SIZE ) % pageSize != 0 ) )
                {
                    throw new InvalidBTreeException( "The pointer to the next page is not valid" );
                }

                // Update the array of processed pages
                setCheckedPage( checkedPages, currentPageIo.getOffset(), pageSize );
            }

            // Now check the B-tree
            long nextBtree = checkBtree( checkedPages, pageIos, pageSize, i == nbBtrees - 1 );

            if ( ( nextBtree == NO_PAGE ) && ( i < nbBtrees - 1 ) )
            {
                throw new InvalidBTreeException( "The pointer to the next B-tree is incorrect" );
            }

            position = nextBtree;
        }
    }


    /**
     * Check the whole file
     */
    private void check()
    {
        try
        {
            // First check the RMheader
            ByteBuffer recordManagerHeader = ByteBuffer.allocate( RECORD_MANAGER_HEADER_SIZE );
            long fileSize = fileChannel.size();

            if ( fileSize < RECORD_MANAGER_HEADER_SIZE )
            {
                throw new InvalidBTreeException( "File size too small : " + fileSize );
            }

            // Read the RMHeader
            fileChannel.read( recordManagerHeader, 0L );
            recordManagerHeader.flip();

            // The page size. It must be a power of 2, and above 16.
            int pageSize = recordManagerHeader.getInt();

            if ( ( pageSize < 0 ) || ( pageSize < 32 ) || ( ( pageSize & ( ~pageSize + 1 ) ) != pageSize ) )
            {
                throw new InvalidBTreeException( "Wrong page size : " + pageSize );
            }

            // Compute the number of pages in this file
            long nbPages = ( fileSize - RECORD_MANAGER_HEADER_SIZE ) / pageSize;

            // The number of trees. It must be at least 2 and > 0
            int nbBtrees = recordManagerHeader.getInt();

            if ( nbBtrees < 0 )
            {
                throw new InvalidBTreeException( "Wrong nb trees : " + nbBtrees );
            }

            // The first free page offset. It must be either -1 or below file size
            // and its value must be a modulo of pageSize
            long firstFreePage = recordManagerHeader.getLong();

            if ( firstFreePage > fileSize )
            {
                throw new InvalidBTreeException( "First free page pointing after the end of the file : "
                    + firstFreePage );
            }

            if ( ( firstFreePage != NO_PAGE ) && ( ( ( firstFreePage - RECORD_MANAGER_HEADER_SIZE ) % pageSize ) != 0 ) )
            {
                throw new InvalidBTreeException( "First free page not pointing to a correct offset : " + firstFreePage );
            }

            int nbPageBits = ( int ) ( nbPages / 64 );

            // Create an array of pages to be checked
            // We use one bit per page. It's 0 when the page
            // hasn't been checked, 1 otherwise.
            long[] checkedPages = new long[nbPageBits + 1];

            // Then the free files
            checkFreePages( checkedPages, pageSize, firstFreePage );

            // The B-trees
            checkBtrees( checkedPages, pageSize, nbBtrees );
        }
        catch ( Exception e )
        {
            // We catch the exception and rethrow it immediately to be able to
            // put a breakpoint here
            e.printStackTrace();
            throw new InvalidBTreeException( "Error : " + e.getMessage() );
        }
    }


    /**
     * Loads a B-tree holding the values of a duplicate key
     * This tree is also called as dups tree or sub tree
     *
     * @param offset the offset of the B-tree header
     * @return the deserialized B-tree
     */
    /* No qualifier */<K, V> BTree<K, V> loadDupsBtree( long btreeHeaderOffset )
    {
        try
        {
            PageIO[] pageIos = readPageIOs( btreeHeaderOffset, Long.MAX_VALUE );

            BTree<K, V> subBtree = BTreeFactory.createPersistedBTree();
            ( ( PersistedBTree<K, V> ) subBtree ).setBtreeHeaderOffset( btreeHeaderOffset );

            loadBtree( pageIos, subBtree );

            return subBtree;
        }
        catch ( Exception e )
        {
            // should not happen
            throw new BTreeCreationException( e );
        }
    }


    /**
     * Check the free pages list
     */
    private void checkFreePages()
    {
        try
        {
            if ( firstFreePage == NO_PAGE )
            {
                return;
            }

            long currentFreePage = firstFreePage;
            Set<Long> seenOffset = new HashSet<Long>();
            seenOffset.add( currentFreePage );

            while ( currentFreePage != NO_PAGE )
            {
                PageIO pageIo = fetchPage( currentFreePage );

    //            System.out.println( "    freePage[" + pageNb + "] : 0x" + Long.toHexString( pageIo.getOffset() ) );

                currentFreePage = pageIo.getNextPage();

                if ( seenOffset.contains( currentFreePage ) )
                {
                    System.out.println( "ERROR !!! The FreePage lists is broken" );
                    dumpFreePages( firstFreePage );
                }
            }

            return;
        }
        catch ( Exception e )
        {
            System.out.println( "Error : " + e.getMessage() );
        }
    }


    /**
     * @see Object#toString()
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        sb.append( "RM free pages : [" );

        if ( firstFreePage != NO_PAGE )
        {
            long current = firstFreePage;
            boolean isFirst = true;

            while ( current != NO_PAGE )
            {
                if ( isFirst )
                {
                    isFirst = false;
                }
                else
                {
                    sb.append( ", " );
                }

                PageIO pageIo;

                try
                {
                    pageIo = fetchPage( current );
                    sb.append( pageIo.getOffset() );
                    current = pageIo.getNextPage();
                }
                catch ( EndOfFileExceededException e )
                {
                    e.printStackTrace();
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                }

            }
        }

        sb.append( "]" );

        return sb.toString();
    }
}
