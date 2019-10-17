public class Inode {

   // private constants
   private final static int blockSize = Disk.blockSize; // # of bytes in 1 block (512)
   private final static short NULL_BLOCK = -1; // represents a null block id/pointer
   
   // public constants
   public final static int iNodesPerBlock = 16; // # of iNodes in 1 block
   public final static int iNodeSize = 32; // fix to 32 bytes
   public final static int directSize = 11; // # direct pointers

   // return codes
   public final static int OK = 0;
   public final static int ERROR_CONFLICT = -1;
   public final static int ERROR_NONSEQUENTIAL = -2;
   public final static int ERROR_NO_INDEX = -3;

   // possible flag values
   public final static short FLAG_UNUSED = 0;
   public final static short FLAG_USED = 1;
   public final static short FLAG_READ = 2;
   public final static short FLAG_WRITE = 3;

   // Inode properties
   public int length; // file size in bytes
   public short count; // # file-table entries pointing to this
   public short flag; // 0 = unused, 1 = used, ...
   public short direct[] = new short[directSize]; // direct pointers
   public short indirect; // a indirect pointer

   Inode() { // a default constructor
      length = 0;
      count = 0;
      flag = 1;
      for (int i = 0; i < directSize; i++)
         direct[i] = -1;
      indirect = -1;
   }

   Inode(short iNumber) { // retrieving inode from disk
      // retrieve the block containing this iNode
      int block = (iNumber / iNodesPerBlock) + 1;
      byte[] buffer = new byte[blockSize]; // holds 1 block
      SysLib.rawread(block, buffer); // read the block into the buffer

      // read iNode properties from the block
      int offset = iNumber % iNodesPerBlock * iNodeSize; // offset in the block
      // read length
      length = SysLib.bytes2int(buffer, offset);
      offset += 4; // offset index by size of int
      // read count
      count = SysLib.bytes2short(buffer, offset);
      offset += 2; // offset index by size of short
      // read flag
      flag = SysLib.bytes2short(buffer, offset);
      offset += 2; // offset index by size of short
      // read direct pointers
      for (int d = 0; d < directSize; d++) {
         direct[d] = SysLib.bytes2short(buffer, offset);
         offset += 2; // offset index by size of short
      }
      // read indirect pointer
      indirect = SysLib.bytes2short(buffer, offset);
      offset += 2; // offset index by size of short
   }

   void toDisk(short iNumber) { // save to disk as the i-th inode

      // retrieve the block containing this iNode
      int block = (iNumber / iNodesPerBlock) + 1;
      byte[] buffer = new byte[blockSize]; // holds 1 block
      SysLib.rawread(block, buffer); // read the block into the buffer

      // write iNode properties into the buffer
      int offset = iNumber % iNodesPerBlock * iNodeSize; // offset in the block
      // write length
      SysLib.int2bytes(length, buffer, offset);
      offset += 4; // offset index by size of int
      // write count
      SysLib.short2bytes(count, buffer, offset);
      offset += 2; // offset index by size of short
      // write flag
      SysLib.short2bytes(flag, buffer, offset);
      offset += 2; // offset index by size of short
      // write direct pointers
      for (int d = 0; d < directSize; d++) {
         SysLib.short2bytes(direct[d], buffer, offset);
         offset += 2; // offset index by size of short
      }
      // write indirect pointer
      SysLib.short2bytes(indirect, buffer, offset);
      offset += 2; // offset index by size of short
      
      // write the buffer back to the block on the disk
      SysLib.rawwrite(block, buffer);
   }

   // return the block pointed to by the indirect pointer
   short getIndexBlockNumber() {
      return indirect;
   }
   
   // set the block pointed to by a direct pointer
   // return pass (true) or fail (false)
   int registerTargetBlock(int offset, short targetBlockNumber) {
      int index = offset / blockSize;
      
      // Register the block in a direct block
      if (index < directSize) {
         for (int i = 0; i < directSize; i++)
            if (direct[index] >= 0) // if the direct pointer is not set 
               return ERROR_CONFLICT; // fail :(
            if (index > 0 && direct[index - 1] < 0) // if the previous direct pointer is not set 
                  return ERROR_NONSEQUENTIAL; // fail :(
            direct[index] = targetBlockNumber; // set the direct pointer
            return OK; // pass :)
      }

      if (indirect < 0) // no indirect block set
         return ERROR_NO_INDEX; // fail

      // Register the block in the indirect index
      byte[] buffer = new byte[blockSize];
      SysLib.rawread(indirect, buffer); // read index block from disk
      
      index -= directSize;
      if (SysLib.bytes2short(buffer, index * 2) > 0) // index position is already registered
         return ERROR_CONFLICT; // fail :(
      SysLib.short2bytes(targetBlockNumber, buffer, index * 2); // register target block to index position

      SysLib.rawwrite(indirect, buffer); // write index block back to disk
      return OK; // pass :)
   }

   // set the block pointed to by the indirect pointer
   // return pass (true) or fail (false)
   boolean registerIndexBlock(short indexBlockNumber) {
      for (int i = 0; i < directSize; i++)
         if (direct[i] < 0) // if a direct pointer is not set 
            return false; // fail :(
      if (indirect >= 0) // if the indirect pointer is already set
         return false; // fail :(
      // set indirect pointer
      indirect = indexBlockNumber;
      // fill the block with null references (-1)
      byte[] buffer = new byte[blockSize];
      for (int i = 0; i < blockSize; i += 2)
         SysLib.short2bytes(NULL_BLOCK, buffer, i);
      SysLib.rawwrite(indexBlockNumber, buffer);
      return true; // pass :)
   }

   // return the block containing a part of this file
   int findTargetBlock(int offset) {
      if (offset < 0)
         return NULL_BLOCK;
      int index = offset / blockSize;
      if (offset < directSize * blockSize) {
         short block = direct[index];
         return block;
      } else if (indirect >= 0) {
         byte[] buffer = new byte[blockSize];
         SysLib.rawread(indirect, buffer); // read the block into the buffer
         index -= directSize; // offset index from end of direct array (end of direct[] == index 0)
         if (index < blockSize) {
            short block = SysLib.bytes2short(buffer, index * 2); // read short from buffer at index
            return block;
         }
      }
      return NULL_BLOCK;
   }
   
   // clear the index block pointer and return the contents of the index block
   byte[] unregisterIndexBlock() {
      if (indirect >= 0) {
         byte[] buffer = new byte[blockSize];
         SysLib.rawread(indirect, buffer);
         indirect = NULL_BLOCK;
         return buffer;
      } else
         return null;
   }
}
