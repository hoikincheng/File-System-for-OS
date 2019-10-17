public class SuperBlock {
    private final int defaultInodeBlocks = 64;
    private final int totalBlockLocation = 0;
    private final int totalInodeLocation = 4;
    private final int freeListLocation = 8;
    private final int defaultTotalBlocks = 1000;

    private final static int blockSize = Disk.blockSize;

    private final static int NULL_BLOCK = -1;

    public int totalBlocks; // the number of disk blocks
    public int inodeBlocks; // the number of inodes
    public int freeList; // the block number of the free list's head

    public SuperBlock(int diskSize) {
        byte[] superBlock = new byte[blockSize];
        int offset = 0;
        SysLib.rawread(0, superBlock); // read from block 0 of disk
        // extract SuperBlock properties
        totalBlocks = SysLib.bytes2int(superBlock, offset);
        offset += 4;
        inodeBlocks = SysLib.bytes2int(superBlock, offset);
        offset += 4;
        freeList = SysLib.bytes2int(superBlock, offset);
        offset += 4;
        // format if necessary
        if ((totalBlocks == diskSize) && (inodeBlocks > 0) && (freeList >= 2)) {
            // do not format
            return;
        } else {
            // format
            totalBlocks = diskSize;
            format();
        }
    }

    // writes back totalBlocks, inodeBlocks, and freeList to disk
    public void sync() {
        byte[] buffer = new byte[blockSize];
        // insert SuperBlock properties into buffer
        int offset = 0;
        SysLib.int2bytes(totalBlocks, buffer, offset);
        offset += 4;
        SysLib.int2bytes(inodeBlocks, buffer, offset);
        offset += 4;
        SysLib.int2bytes(freeList, buffer, offset);
        offset += 4;
        SysLib.rawwrite(0, buffer); // write to block 0 of disk
        SysLib.cerr("Superblock synchronized\n");
    }

    // dequeues the top block from the free list
    public int getFreeBlock() {
        int returnBlock = freeList; // the first free block

        // look for the first free block, if any
        if (returnBlock != NULL_BLOCK) {
            byte[] buffer = new byte[blockSize];
            SysLib.rawread(returnBlock, buffer); // read selected free block from disk
            freeList = SysLib.bytes2int(buffer, 0); // get next free block from buffer
            SysLib.int2bytes(0, buffer, 0); // erase the next free block pointer, so all data in the block is 0
            SysLib.rawwrite(returnBlock, buffer); // write empty block back to disk
        }
        return returnBlock; // return the dequeued free block number
    }
    
    public boolean returnBlock(int blockNumber) {
        if (blockNumber >= 0) {
            byte[] buffer = new byte[blockSize];
            for (int i = 0; i < blockSize; i++)
                buffer[i] = 0;
            SysLib.int2bytes(freeList, buffer, 0);
            SysLib.rawwrite(blockNumber, buffer);
            freeList = blockNumber;
            return true;
        }
        return false;
    }
    
    public void format() {
        format(defaultInodeBlocks);
    }

    public boolean format(int totalFiles) {
        // Set to default if total files doesn't make sense
        if (totalFiles < 0)
            return false; // fail
        inodeBlocks = totalFiles;

        // initialize all Inodes as "unused" and write to disk
        Inode freeInode;
        for (short iNum = 0; iNum < inodeBlocks; iNum++) {
            freeInode = new Inode(); // empty iNode (not read from disk)
            freeInode.flag = Inode.FLAG_UNUSED;
            freeInode.toDisk(iNum);
        }

        // The first free block after SuperBlock and inodeBlocks.
        // Adding 2 to the total size of inodes to account
        // for SuperBlock and uneven division.
        freeList = 2 + inodeBlocks * Inode.iNodeSize / blockSize;

        // setting up free blocks
        // start at first free block (after SuperBlock and Inodes)
        for (int blockNum = freeList; blockNum <= totalBlocks - 2; blockNum++) {
            byte[] buffer = new byte[blockSize]; // buffer for new free block
            java.util.Arrays.fill(buffer, (byte) 0); // ensure that buffer is filled with 0
            SysLib.int2bytes(blockNum + 1, buffer, 0); // point to next free block
            SysLib.rawwrite(blockNum, buffer); // write block to disk
        }
        // setting up the last free block
        {
            byte[] buffer = new byte[blockSize]; // buffer for new free block
            java.util.Arrays.fill(buffer, (byte) 0); // ensure that buffer is filled with 0
            SysLib.int2bytes(NULL_BLOCK, buffer, 0); // point to NULL next free block
            SysLib.rawwrite(totalBlocks - 1, buffer); // write to last free block on disk
        }
        sync();
        return true; // succeed
    }
}
