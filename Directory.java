public class Directory {
    private static int maxChars = 30; // max characters of each file name
    private static int BLOCK = 4;
    private static short ERROR = -1;

    // Directory entries
    private int fsize[]; // each element stores a different file size.
    private char fnames[][]; // each element stores a different file name.

    public Directory(int maxInumber) {
        fsize = new int[maxInumber]; // maxInumber = max files
        for (int i = 0; i < maxInumber; i++)
            fsize[i] = 0; // all file size initialized to 0
        fnames = new char[maxInumber][maxChars];
        String root = "/"; // entry(inode) 0 is "/"
        fsize[0] = root.length(); // fsize[0] is the size of "/".
        root.getChars(0, fsize[0], fnames[0], 0); // fnames[0] includes "/"
    }

    public void bytes2directory(byte data[]) {
        // assumes data[] received directory information from disk
        int offset = 0;
        for (int i = 0; i < fsize.length; i++) {
            fsize[i] = SysLib.bytes2int(data, offset);
            offset += BLOCK;
        }

        // initializes the Directory instance with this data[]
        for (int i = 0; i < fnames.length; i++) {
            String tempStr = new String(data, offset, maxChars * 2); // byte[] to string
            tempStr.getChars(0, fsize[i], fnames[i], 0); // string to char[]
            offset += maxChars * 2;
        }
    }

    public byte[] directory2bytes() {
        // converts and return Directory information into a plain byte array
        // this byte array will be written back to disk
        // note: only meaningfull directory information should be converted
        // into bytes.
        byte[] ret = new byte[fsize.length * BLOCK + fnames.length * maxChars * 2];
        int offset = 0;
        for (int j = 0; j < fsize.length; j++) {
            SysLib.int2bytes(fsize[j], ret, offset);
            offset += BLOCK;
        }
        for (int j = 0; j < fnames.length; j++) {
            String tempStr = new String(fnames[j], 0, fsize[j]); // char[] to string
            byte[] bytes = tempStr.getBytes(); // char[] to byte[]
            System.arraycopy(bytes, 0, ret, offset, bytes.length); 
            offset += maxChars * 2;
        }
        return ret;
    }

    public short ialloc(String filename) {
        // filename is the one of a file to be created.
        for (short i = 0; i < fsize.length; i++) {
            // no file name defined
            if (fsize[i] == 0) {
                // allocates a new inode number for this filename
                fsize[i] = Math.min(filename.length(), maxChars); // copy name size to fsize
                filename.getChars(0, fsize[i], fnames[i], 0); // copy filename to fnames
                return i;
            }
        }
        return ERROR;
    }

    // deallocates this inumber (inode number)
    public boolean ifree(short iNumber) {
        if (iNumber < maxChars && fsize[iNumber] > 0) {
            fsize[iNumber] = 0;
            // returns true if succeeded
            return true;
        }
        // returns false if failed
        return false;
    }

    public short namei(String filename) {
        for (short i = 0; i < fsize.length; i++) {
            if (fsize[i] == filename.length()) {
                String tempStr = new String(fnames[i], 0, fsize[i]);
                if (filename.equals(tempStr)) {
                    // returns the inumber corresponding to this filename
                    return i;
                }
            }
        }
        return ERROR;
    }
}
