import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/***
 * Utility class to access, read and write pieces to file.
 * The associated file should have been created before the use of this class object.
 * */
public class FileObject {
    private RandomAccessFile fileAccess;
    private File file;
    private int fileSize;
    private int pieceSize;
    private String fileName;

    FileObject(String fileName, int fileSize, int pieceSize) throws FileNotFoundException {
        try {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.pieceSize = pieceSize;
            file = new File(fileName);
            if (!file.exists()) {
                throw new FileNotFoundException();
            }
            fileAccess = new RandomAccessFile(file, "rw");
        } catch (FileNotFoundException ex) {
        }
    }

    public void cleanUp(){
        try {
            fileAccess.close();
        }catch (IOException ex){
        }
    }
    /**
     * Read the piece with pieceIndex from the file.
     */
    public byte[] readPiece(int pieceIndex, int size) {
        byte[] piece = new byte[size];
        try {
            long offSet = (pieceIndex - 1) * pieceSize;
            fileAccess.seek(offSet);
            fileAccess.read(piece, 0, size);
        } catch (IOException ex) {
            try {
                fileAccess.close();
            } catch (IOException ex1) {
            }
        }
        return piece;
    }

    /***
     * Write piece to the file.
     **/
    public boolean writePiece(int pieceIndex, byte[] piece, int size) {
        try {
            long offSet = (pieceIndex - 1) * pieceSize;
            fileAccess.seek(offSet);
            fileAccess.write(piece, 0, size);
            return true;
        } catch (IOException ex) {
            try {
                fileAccess.close();
            } catch (IOException ex1) {
            }
        }
        return false;
    }
}
