public class FileSystem {
    private static final short blockSize = Disk.blockSize;

    private static final int ERROR = -1; // error return code
    private final static short NULL_BLOCK = -1; // represents a null block id/pointer

    private SuperBlock superblock;
    private Directory directory;
    private FileTable filetable;
    private final int SEEK_SET = 0;
    private final int SEEK_CUR = 1;
    private final int SEEK_END = 2;

    public FileSystem(int diskBlocks) {
        superblock = new SuperBlock(diskBlocks);
        directory = new Directory(superblock.inodeBlocks);
        filetable = new FileTable(directory);

        // read the "/" file from disk
        FileTableEntry dirEnt = open("/", "r"); // open root directory file for reading
        int dirSize = fsize(dirEnt);
        if (dirSize > 0) {
            // the directory has some data
            byte[] buffer = new byte[dirSize];
            read(dirEnt, buffer); // read directory data from file
            directory.bytes2directory(buffer); // update directory data with data from file
        }
        close(dirEnt); // close the root directory file
    }

    public void sync() {
        FileTableEntry root = open("/", "w"); // open root directory file for writing
        byte[] buffer = directory.directory2bytes(); // convert directory to bytes
        write(root, buffer); // write directory data to root file
        close(root); // close the root directory file
        superblock.sync(); // sync SuperBlock state with disk
    }

    public boolean format(int files)
    {   // busy wait while filetable is not empty (files are in use)
        while(filetable.fempty() == false) { }
        superblock.format(files);
        // reinitialize structures
        directory = new Directory(superblock.inodeBlocks);
        filetable = new FileTable(directory);
        return true; // success
    }

    public FileTableEntry open(String filename, String mode) {
        FileTableEntry ftEnt = filetable.falloc(filename, mode);
        if ( mode.compareTo("w") == 0 && !deallocAllBlocks(ftEnt) ) // fail to dealloc blocks before write
                return null; // failure
        return ftEnt; // success
    }

    public boolean close(FileTableEntry ftEnt) {
        synchronized (ftEnt) {
            ftEnt.count = ftEnt.count - 1; // release 1 user of this file
            if (ftEnt.count > 0) { // file is still in use
                return true; // success (don't try to free)
            }
        }
        return filetable.ffree(ftEnt); // try to free the file
    }

    public int read(FileTableEntry ftEnt, byte[] buffer) {
        if (ftEnt.mode.compareTo("w") == 0 || ftEnt.mode.compareTo("a") == 0) {
            return ERROR; // not read mode
        }
        int readCount = 0; // # of bytes that have been read
        synchronized (ftEnt) {
            int moreBytes; // # of bytes read in the last iteration
            int seekBlock; // block # of seek pointer
            for (int remainingBytes = buffer.length; /* max # of bytes to read */
                    remainingBytes > 0 && /* read until buffer is full... */
                            ftEnt.seekPtr < fsize(ftEnt) && /* or until end of the file... */
                            (seekBlock = ftEnt.inode
                                    .findTargetBlock(ftEnt.seekPtr)) != ERROR; /*
                                                                                * while there is a data block for the
                                                                                * current seek position
                                                                                */
                    remainingBytes -= moreBytes) /*
                                                  * decrease max # of bytes to be read by the # of bytes that were read
                                                  */
            {
                byte[] blockBuffer = new byte[blockSize]; // allocate a buffer to read a block into
                SysLib.rawread(seekBlock, blockBuffer); // read the block containing the seek pointer
                int bufferOffset = ftEnt.seekPtr % blockSize; // byte index of seek position in the block
                int remainingBlockBytes = blockSize - bufferOffset; // # of unread bytes in the block
                int remainingFileBytes = fsize(ftEnt) - ftEnt.seekPtr; // # of unread bytes in the file
                moreBytes = Math.min(Math.min(remainingBlockBytes, remainingBytes), remainingFileBytes); // # of bytes
                                                                                                         // read (limit
                                                                                                         // read to the
                                                                                                         // end of the
                                                                                                         // file and
                                                                                                         // block)
                System.arraycopy(blockBuffer, bufferOffset, buffer, readCount, moreBytes); // append the bytes read to
                                                                                           // buffer
                ftEnt.seekPtr += moreBytes; // move seek pointer forward
                readCount += moreBytes; // count the total # of bytes read
            }
            return readCount; // return the total # of bytes read
        }
    }

    public int write(FileTableEntry ftEnt, byte[] buffer) {
        if (ftEnt.mode.compareTo("r") == 0) {
            return ERROR; // not write or append mode
        }
        synchronized (ftEnt) {
            int count; // # of bytes written (per block)
            int total = 0; // total # of bytes written
            for (int i = buffer.length; i > 0; i -= count) {
                byte[] blockBuffer; // buffer to temporarily read block data into
                int currentBlock = ftEnt.inode.findTargetBlock(ftEnt.seekPtr); // get data block for the current file
                                                                               // seek position
                if (currentBlock == ERROR) { // data block not registered
                    short freeBlock = (short) superblock.getFreeBlock(); // allocate a new block
                    switch (ftEnt.inode.registerTargetBlock(ftEnt.seekPtr, freeBlock)) { // register the block in the
                                                                                         // inode
                    case Inode.OK: // all good. everything is fine. :)
                        break; // keep calm and carry on (will write to the block)
                    case Inode.ERROR_CONFLICT: // attempted to register the block where there was already a block
                                               // registered
                    case Inode.ERROR_NONSEQUENTIAL: // attempted to register the block out of order
                        SysLib.cerr("ThreadOS: Data block rejected by file node\n");
                        return ERROR; // fail :(
                    case Inode.ERROR_NO_INDEX: // inode does not have an index block yet
                        short indexBlock = (short) superblock.getFreeBlock(); // allocate a new block for the index
                        if (!ftEnt.inode.registerIndexBlock(indexBlock)) { // try to register the index block to the
                                                                           // inode
                            SysLib.cerr("ThreadOS: Index block rejected by file node\n");
                            return ERROR; // fail :(
                        }
                        if (ftEnt.inode.registerTargetBlock(ftEnt.seekPtr, freeBlock) == 0) // try to register the data
                                                                                            // block again
                            break;
                        SysLib.cerr("ThreadOS: Data block rejected by indexed file node\n");
                        return ERROR;
                    }
                    currentBlock = freeBlock; // use the newly allocated block
                }
                blockBuffer = new byte[blockSize];
                if (SysLib.rawread(currentBlock, blockBuffer) == ERROR) { // failure to read block from disk to
                                                                          // blockBuffer
                    SysLib.cerr("ThreadOS: Failed to get data from disk before writing. Abandon ship!\n");
                    System.exit(2);
                }
                int offset = ftEnt.seekPtr % blockSize; // offset of seek pointer in a block
                int remainder = blockSize - offset; // # of bytes remaining in the block
                count = Math.min(remainder, i); // # of bytes to copy from this block
                System.arraycopy(buffer, total, blockBuffer, offset, count); // copy to blockBuffer
                SysLib.rawwrite(currentBlock, blockBuffer); // write blockBuffer to disk
                ftEnt.seekPtr += count; // move seek pointer forward
                total += count; // add bytes written to total
                if (ftEnt.seekPtr <= ftEnt.inode.length) // if the current seek position still fits in the file...
                    continue; // ...then keep calm and carry on (do it again)
                ftEnt.inode.length = ftEnt.seekPtr; // ...else update the length of the file to fit the seek position
            }
            ftEnt.inode.toDisk(ftEnt.iNumber); // write the updated inode to disk
            return total; // return the total # of bytes written
        }
    }

    public int fsize(FileTableEntry ftEnt) {
        synchronized (ftEnt) {
            return ftEnt.inode.length;
        }
    }

    public int seek(FileTableEntry ftEnt, int offset, int whence) {
        synchronized (ftEnt) {
            switch (whence) {
            case SEEK_SET:
                // seek relative to the start of the file
                if (offset >= 0 && offset <= fsize(ftEnt)) {
                    ftEnt.seekPtr = offset;
                    break;
                }
                return ERROR;

            case SEEK_CUR:
                // seek relative to the current seek position
                int newPosition = ftEnt.seekPtr + offset;
                // accepts negative offset
                if (newPosition >= 0 && newPosition <= fsize(ftEnt)) {
                    ftEnt.seekPtr = newPosition;
                    break;
                }
                return ERROR;

            case SEEK_END:
                // seek from the end of the file
                int newLocation = fsize(ftEnt) + offset;
                // accepts negative offset
                if (newLocation >= 0 && newLocation <= fsize(ftEnt)) {
                    ftEnt.seekPtr = newLocation;
                    break;
                }
                return ERROR;

            default:
                return ERROR;
            }
            return ftEnt.seekPtr; // return the new seek position
        }
    }

    public boolean delete(String filename) {
        FileTableEntry ftEnt = open(filename, "w"); // acquire and clear file by opening for writing
        return (close(ftEnt) && directory.ifree(ftEnt.iNumber)); // try to release and unregister the file
    }
    
    private boolean deallocAllBlocks(FileTableEntry ftEnt) {
        // only deallocate blocks when there is exactly one file table entry
        // opening the file
        if (ftEnt.inode.count != 1) {
            return false;
        }
        // unregister the index block from the Inode
        byte[] indexBuffer = ftEnt.inode.unregisterIndexBlock();
        int num;
        // deallocate the index blocks
        if (indexBuffer != null) {
            short blockNum;
            num = 0;
            // repeat recursively until there is not another index block
            while ((blockNum = SysLib.bytes2short(indexBuffer, num)) != NULL_BLOCK) // get next index block
                superblock.returnBlock((int) blockNum); // add the block to set of free blocks
        }
        // deallocate the direct blocks
        for (short blockNum = 0; blockNum < Inode.directSize; blockNum++) {
            if (ftEnt.inode.direct[blockNum] == NULL_BLOCK)
                continue;
            superblock.returnBlock((int) ftEnt.inode.direct[blockNum]); // add the block to set of free blocks
            ftEnt.inode.direct[blockNum] = NULL_BLOCK; // unregister the block from the Inode
        }
        ftEnt.inode.toDisk(ftEnt.iNumber); //write the Inode back to disk
        return true;
    }

}
