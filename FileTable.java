import java.util.Vector;

public class FileTable {
    private Vector table; // the actual entity of this file table
    private Directory dir; // the root directory

    // constructor
    public FileTable(Directory directory) {
        table = new Vector(); // instantiate a file (structure) table
        dir = directory; // receive a reference to the Director
    } // from the file system

    // major public methods
    public synchronized FileTableEntry falloc(String filename, String mode) {
        // allocate a new file (structure) table entry for this file name
        // allocate/retrieve and register the corresponding inode using dir
        // increment this inode's count
        // immediately write back this inode to the disk
        // return a reference to this file (structure) table entry
        Inode inode = null;
        short inumber = 0;
        boolean doAllocInode;

        while (true) { // loop until break
            doAllocInode = false; // by default, do not allocate a new inode
            inumber = (filename == "/" ? 0 : dir.namei(filename)); // get inode number of root or from directory index
            if (inumber < 0) { // file is not registered in the file table
                doAllocInode = true; // allocate a new inode
                break;
            }
            inode = new Inode(inumber);  // read inode from disk
            if (mode.compareTo("r") == 0) { // read mode
                if (inode.flag == Inode.FLAG_UNUSED || inode.flag == Inode.FLAG_USED) { // inode is not flagged as read or write
                    inode.flag = Inode.FLAG_USED; // flag inode as used
                    break; // skip ialloc
                }
                try {
                    wait(); // wait for a ffree
                } catch (InterruptedException ex) {}
                continue; // check it again
            } else { // write or append mode
                if (inode.flag == Inode.FLAG_UNUSED || inode.flag == Inode.FLAG_WRITE) { // inode is not used or not flagged for read mode
                    inode.flag = Inode.FLAG_READ; // flag inode for read mode
                    break; // skip ialloc
                }
                if (inode.flag == Inode.FLAG_USED || inode.flag == Inode.FLAG_READ) { // inode is flagged as used or for reading
                    inode.flag = Inode.FLAG_WRITE; // flag inode for writing
                    inode.toDisk(inumber); // write inode to disk
                }
                try {
                    wait(); // wait for ffree
                } catch (InterruptedException ex) {}
            }
        }

        if (doAllocInode) {
            if (mode.compareTo("r") == 0) // is read mode
                return null; // fail :(
            inumber = dir.ialloc(filename); // allocate for a new inode
            inode = new Inode(); // initialize a new blank inode
            inode.flag = Inode.FLAG_READ; // flag inode for reading
        }

        inode.count += 1; // add 1 user to the inode
        inode.toDisk(inumber); // write the inode to disk
        FileTableEntry ftEnt = new FileTableEntry(inode, inumber, mode); // generate a new file table entry for the inode
        table.addElement(ftEnt); // synchronized add()
        return ftEnt; // pass :)
    }

    public synchronized boolean ffree(FileTableEntry ftEnt) {
        // receive a file table entry reference
        // save the corresponding inode to the disk
        // free this file table entry.
        // return true if this file table entry found in my table

        if (table.removeElement(ftEnt)) {
            ftEnt.inode.count -= 1; // release 1 user of the inode
            if (ftEnt.inode.flag == Inode.FLAG_USED || ftEnt.inode.flag == Inode.FLAG_READ)
                ftEnt.inode.flag = Inode.FLAG_UNUSED; // release the inode
            ftEnt.inode.toDisk(ftEnt.iNumber); // save the inode to disk
            notify(); // wake an falloc
            return true; // entry found in table
        }
        return false; // entry not found in table
    }

    public synchronized boolean fempty() {
        return table.isEmpty(); // return if table is empty
    } // should be called before starting a format
}
