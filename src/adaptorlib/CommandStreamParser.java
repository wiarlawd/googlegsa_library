// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package adaptorlib;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the adaptor data format into individual commands with associated data.
 *
 * This format is used for communication between the adaptor library and various command line
 * adaptor components (lister, retriever, transformer, authorizor, etc.). It supports responses
 * coming back from the command line adaptor implementation. The format supports a mixture of
 * character and binary data. All character data must be encoded in UTF-8.<p>
 *
 * <h3>Header Format</h3>
 *
 * Communications (via either file or stream) begin with the header:<p>
 *
 * {@code GSA Adaptor Data Version 1 [<delimiter>]}<p>
 *
 * The version number must be proceeded by a single space and followed by a single space. The
 * version number may increase in the future should the format be enhanced.<p>
 *
 * The string between the two square brackets will be used as the delimiter for the remainder of the
 * file being read or for the duration of the communication session.<p>
 *
 * Care must be taken that the delimiter character string can never occur in a document ID, metadata
 * name, metadata value, user name, or any other data that will be represented using the format with
 * the exception of document contents, which can contain the delimiter string. The safest delimiter
 * is likely to be the null character (the character with a value of zero). This character is
 * unlikely to be present in existing names, paths, metadata, etc. Another possible choice is the
 * newline character, though in many systems it is possible for this character to be present in
 * document names and document paths, etc. If in doubt, the null character is recommended. A
 * delimiter can be made up of more than one character so it is possible to have a delimiter that is
 * <CR><LF> or a highly unique string (such as a GUID) that has an exceptionally low probability of
 * occurring in the data.<p>
 *
 * The following characters may not be used in the delimiter:<p> 'A'-'Z', 'a'-'z' and '0'-'9' the
 * alphanumeric characters<br> ':'  colon<br> '/'  slash<br> '-'  hyphen<br> '_'  underscore<br> ' '
 * space<br> '=' equals<br> '+' plus<br> '[' left square bracket<br> ']' right square bracket<p>
 *
 * <h3>Body Format</h3> Elements in the file start with one of the following commands. Commands
 * where data precedes the next delimiter include an equal sign. Commands that are immediately
 * followed by a delimiter do not include an equal sign. The first command must specify a document
 * ID ("id=" or "id-list"). Command that don't specify a document ID are associated with the most
 * recent previously specified document ID.<p>
 *
 * <h1>Common Commands:</h1>
 *
 * "id=" -- specifies a document id<p>
 *
 * "id-list" -- Starts a list of document ids each separated by
 * the specified delimiter, the list is terminated by two consecutive delimiters or EOS
 * (End-Of-Stream). ids in an id-list cannot have any of the associated commands listed below.<p>
 *
 * "repository-unavailable=" -- the document repository is unavailable. The string following the "="
 * character includes additional information that will be logged with the error.
 *
 *
 * <h1>Lister Commands:</h1>
 *
 * "last-modified=" -- specifies the last time the document or its metadata has changed in
 * milliseconds from epoch. If last-modified is specified and the document has never been crawled
 * before or have been crawled prior to the last-modified time then the document will be marked as
 * "crawl-immediate".<p>
 *
 * "crawl-immediately" -- Increases the crawling priority of the document such
 * that the GSA will retrieve it sooner than normally crawled documents.<p>
 *
 * "crawl-once" -- specifies that the document will be crawled by the
 *                 GSA one time but then never re-crawled.<p>
 *
 * "lock" -- Causes the document to remain in the index unless explicitly removed.
 * Failure to retrieve the document during re-crawling will not result in
 * removal of the document. If every document in the GSA is
 * locked then locked document may be forced out when maximum capacity is
 * reached.<p>
 *
 * "delete" -- this document should be deleted from the GSA index.<p>
 *
 * <h1>Retriever Commands:</h1>
 *
 * "up-to-date" -- specifies that the document is up-to-date with respect to its last crawled
 * time.<p>
 *
 * "not-found" -- the document does not exists in the repository<p>
 *
 * "mime-type=" -- specifies the document's mime-type. If unspecified then the GSA will
 * automatically assign a type to the document. <p>
 *
 * "meta-value=" -- specifies a metadata value associated with
 * immediately preceding metadata-name<p>
 *
 * "content" -- signals the beginning of binary content which
 * continues to the end of the file or stream<p>
 *
 *
 * End-of-stream terminates the data transmission. Multiple consecutive delimiters are collapsed
 * into a single delimiter and terminates the current id-list should one exist.<p>
 *
 * Unrecognized commands generate a warning but are otherwise ignored.
 *
 * <h3>Examples</h3>
 *
 * Example 1:<p>
 *
 * <pre>
 * {@code
 * GSA Adaptor Data Version 1 [<delimiter>]
 * id-list
 * /home/repository/docs/file1
 * /home/repository/docs/file2
 * /home/repository/docs/file3
 * /home/repository/docs/file4
 * /home/repository/docs/file5
 * }
 * </pre>
 *
 * Example 2:<p>
 *
 * <pre>
 * {@code
 * GSA Adaptor Data Version 1 [<delimiter>]
 * id=/home/repository/docs/file1
 * id=/home/repository/docs/file2
 * crawl-immediately
 * last-modified=20110803 16:07:23
 *
 * meta-name=Department
 * meta-content=Engineering
 *
 * meta-name=Creator
 * meta-content=howardhawks
 *
 * id=/home/repository/docs/file3
 * id=/home/repository/docs/file4
 * id=/home/repository/docs/file5
 * }
 * </pre>
 */
public class CommandStreamParser {


  private static enum Operation {
    ID,
    LAST_MODIFIED,
    CRAWL_IMMEDIATELY,
    CRAWL_ONCE,
    LOCK,
    DELETE,
    UP_TO_DATE,
    NOT_FOUND,
    MIME_TYPE,
    META_NAME,
    META_VALUE,
    CONTENT
  }

  private static final String HEADER_PREFIX = "GSA Adaptor Data Version";
  private static final String DISALLOWED_DELIMITER_CHARS_REGEX = "[a-zA-Z0-9:/\\-_ =\\+\\[\\]]";
  private static final Charset CHARSET = Charset.forName("UTF-8");

  private static final Map<String, Operation> STRING_TO_OPERATION;

  static {
    Map<String, Operation> stringToOperation = new HashMap<String, Operation>();
    stringToOperation.put("id", Operation.ID);
    stringToOperation.put("last-modified", Operation.LAST_MODIFIED);
    stringToOperation.put("crawl-immediately", Operation.CRAWL_IMMEDIATELY);
    stringToOperation.put("crawl-once", Operation.CRAWL_ONCE);
    stringToOperation.put("lock", Operation.LOCK);
    stringToOperation.put("delete", Operation.DELETE);
    stringToOperation.put("up-to-date", Operation.UP_TO_DATE);
    stringToOperation.put("not-found", Operation.NOT_FOUND);
    stringToOperation.put("mime-type", Operation.MIME_TYPE);
    stringToOperation.put("meta-name", Operation.META_NAME);
    stringToOperation.put("meta-value", Operation.META_VALUE);
    stringToOperation.put("content", Operation.CONTENT);

    // Confirm that every operation is in the map exactly once
    Collection<Operation> opsInMap = stringToOperation.values();
    Operation[] opsInEnum = Operation.class.getEnumConstants();

    if (!opsInMap.containsAll(Arrays.asList(opsInEnum)) || opsInMap.size() != opsInEnum.length) {
      throw new RuntimeException("Internal Error: Every operation must have exactly one"
          + "entry in the stringToOperation map");
    }

    STRING_TO_OPERATION = Collections.unmodifiableMap(stringToOperation);
  }

  private InputStream inputStream;
  private int versionNumber = 0;
  private String delimiter;
  private boolean inIdList;
  private CharsetDecoder charsetDecoder = CHARSET.newDecoder();

  /** */
  public static class RetrieverInfo {

    private boolean upToDate;
    private boolean notFound;
    private DocId docId;
    private String mimeType;
    private Metadata metadata;
    private byte[] contents;

    RetrieverInfo(DocId docId, Metadata metadata, byte[] contents, boolean upToDate,
        String mimeType, boolean notFound) {
      this.docId = docId;
      this.metadata = metadata;
      this.contents = contents;
      this.upToDate = upToDate;
      this.mimeType = mimeType;
      this.notFound = notFound;
    }

    public String getMimeType() {
      return mimeType;
    }

    public boolean isUpToDate() {
      return upToDate;
    }

    public boolean notFound() {
      return notFound;
    }

    public DocId getDocId() {
      return docId;
    }

    public Metadata getMetadata() {
      return metadata;
    }

    public byte[] getContents() {
      return contents;
    }
  }

  /** */
  private static class Command {

    private Operation operation;
    private String argument;
    private byte[] contents;

    Command(Operation operation, String argument, byte[] contents) {
      this.operation = operation;
      this.argument = argument;
      this.contents = contents;
    }

    public Operation getOperation() {
      return operation;
    }

    public String getArgument() {
      return argument;
    }

    public byte[] getContents() {
      return contents;
    }
  }

  public CommandStreamParser(InputStream inputStream) {
    this.inputStream = inputStream;
    inIdList = false;
  }

  public int getVersionNumber() throws IOException {
    checkHeader();
    return versionNumber;
  }

  public ArrayList<DocIdPusher.Record> readFromLister() throws IOException {
    ArrayList<DocIdPusher.Record> result = new ArrayList<DocIdPusher.Record>();
    String docId = null;
    String lastModified = null;
    boolean crawlOnce = false;
    boolean crawlImmediately = false;
    boolean lock = false;
    boolean deleteDocument = false;
    Command command = readCommand();

    // Starting out at end-of-stream so return an empty list.
    if (command == null) {
      return result;
    }

    // The first operation must be a doc ID.
    if (command.getOperation() != Operation.ID) {
      throw new IOException("Lister Error: the first operator must be a document ID. "
          + " Instead encountered '" + command.getOperation() + "'.");
    }
    while (command != null) {
      switch (command.getOperation()) {
        case ID:
          if (docId != null) {
            // TODO(johnfelton) add lister options when API is available
            result.add(new DocIdPusher.Record.Builder(new DocId(docId)).build());
          }
          docId = command.getArgument();
          lastModified = null;
          crawlOnce = false;
          crawlImmediately = false;
          lock = false;
          deleteDocument = false;
          break;
        case LAST_MODIFIED:
          lastModified = command.getArgument();
          break;
        case CRAWL_IMMEDIATELY:
          crawlImmediately = true;
          break;
        case CRAWL_ONCE:
          crawlOnce = true;
          break;
        case LOCK:
          lock = true;
          break;
        case DELETE:
          deleteDocument = true;
          break;
        default:
          throw new IOException("Lister Error: invalid operation: '" + command.getArgument() + "");
      }
      command = readCommand();
    }
    // TODO(johnfelton) add lister options when API is available
    result.add(new DocIdPusher.Record.Builder(new DocId(docId)).build());

    return result;
  }

  public RetrieverInfo readFromRetriever() throws IOException {

    Metadata.Builder metadata = new Metadata.Builder();
    byte[] content = null;
    boolean upToDate = false;
    boolean notFound = false;
    String mimeType = null;
    Command command = readCommand();

    if (command == null) {
      throw new IOException("Invalid or missing retriever data.");
    } else if (command.getOperation() != Operation.ID) {
      throw new IOException("Retriever Error: the first operator must be a document ID. "
          + " Instead encountered '" + command.getOperation() + "'.");
    }

    String docId = command.getArgument();
    command = readCommand();
    while (command != null) {
      switch (command.getOperation()) {
        case ID:
          throw new IOException("Only one document ID can be specified in a retriever message");
        case CONTENT:
          content = command.getContents();
          break;
        case META_NAME:
          String metaName = command.getArgument();
          command = readCommand();
          if (command == null || command.getOperation() != Operation.META_VALUE) {
            throw new IOException("meta-name must be immediately followed by meta-value");
          }
          metadata.add(MetaItem.raw(metaName, command.getArgument()));
          break;
        case UP_TO_DATE:
          upToDate = true;
          break;
        case NOT_FOUND:
          notFound = true;
          break;
        case MIME_TYPE:
          mimeType = command.getArgument();
          break;
        default:
          throw new IOException(
              "Retriever Error: invalid operation: '" + command.getArgument() + "");
      }
      command = readCommand();
    }

    return new RetrieverInfo(new DocId(docId), metadata.build(),
        content, upToDate, mimeType, notFound);
  }

  /**
   * Read a command from the command stream
   *
   * @return The next command from the command stream. for end-of-steam null is returned.
   * @throws IOException on stream read error
   */
  private Command readCommand() throws IOException {

    Command result = null;

    while (result == null) {
      String commandTokens[] = parseNextLine();
      if (commandTokens == null) {
        return null;
      } else if ((commandTokens[0].equals("repository-unavailable"))) {
        throw new IOException("Error: repository unavailable. "
            + (commandTokens.length > 1 ? commandTokens[1] : ""));
      }

      Operation operation = STRING_TO_OPERATION.get(commandTokens[0]);
      // Skip over unrecognized commands
      if (operation == null) {
        // TODO(johnfelton) add a warning about an unrecognized command
        continue;
      }

      String argument = null;
      byte content[] = null;

      if (commandTokens.length > 1) {
        argument = commandTokens[1];
      }

      if (operation == Operation.CONTENT) {
        content = readBytesUntilEnd();
      }
      result = new Command(operation, argument, content);
    }
    return result;
  }

  private String[] parseNextLine() throws IOException {
    checkHeader();
    String line = "";
    while (line.length() == 0) {
      line = readCharsUntilMarker(delimiter);
      // On End-Of-Stream return the end-message command
      if (line == null) {
        return null;
      }
      // If nothing is between the last delimiter and this one then exit ID list mode
      if (inIdList && line.length() == 0) {
        inIdList = false;
      } else if (!inIdList && line.equals("id-list")) {
        inIdList = true;
        line = ""; // loop again
      }
    }
    if (inIdList) {
      return new String[]{"id", line};
    }
    return line.split("=", 2);
  }

  /**
   * Read and verify the data format header if needed.
   */
  private void checkHeader() throws IOException {
    if (this.delimiter != null) {
      return;
    }

    String line = readCharsUntilMarker("[");
    if ((line == null) || (line.length() < HEADER_PREFIX.length()) ||
        !line.substring(0, HEADER_PREFIX.length()).equals(HEADER_PREFIX)) {
      throw new IOException("Adaptor data must begin with '" + HEADER_PREFIX + "'");
    }

    String versionNumberString = line.substring(HEADER_PREFIX.length());
    if (versionNumberString.length() < 3) {
      throw new IOException("Format version '" + versionNumberString + "' is invalid. " +
          "The version must be at least one digit with one leading space and one trailing space.");
    }

    delimiter = readCharsUntilMarker("]");
    if ((delimiter == null) || (delimiter.length() < 1)) {
      throw new IOException("Delimiter must be at least one character long.");
    }

    Pattern pattern = Pattern.compile(DISALLOWED_DELIMITER_CHARS_REGEX);
    Matcher matcher = pattern.matcher(delimiter);

    if (matcher.find()) {
      throw new IOException("Invalid character in delimiter.");
    }

    try {
      versionNumber = Integer.parseInt(versionNumberString.trim());
    } catch (NumberFormatException e) {
      throw new IOException("Format version '" + versionNumberString + "' is invalid.");
    }
  }


  private byte[] readBytesUntilMarker(byte[] marker) throws IOException {

    if (marker.length == 0) {
      throw new IOException("Internal Error: Marker length must be greater than zero.");
    }
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    int matchPosition = 0;
    int nextByte = 0;

    while (matchPosition < marker.length) {
      nextByte = inputStream.read();
      if (nextByte == ((int) marker[matchPosition] & 0xff)) {
        matchPosition += 1;
      } else {
        if (matchPosition > 0) {
          byteArrayOutputStream.write(marker, 0, matchPosition);
          matchPosition = 0;
        }
        if (nextByte == -1) {
          break;
        } else {
          byteArrayOutputStream.write(nextByte);
        }
      }
    }
    byte[] result = byteArrayOutputStream.toByteArray();
    if (nextByte == -1 && result.length == 0) {
      return null;
    } else {
      return result;
    }

  }

  private String readCharsUntilMarker(String marker) throws IOException {
    byte[] byteMarker = marker.getBytes(CHARSET);
    byte[] bytes = readBytesUntilMarker(byteMarker);
    if (bytes == null) {
      return null;
    }
    return charsetDecoder.decode(ByteBuffer.wrap(bytes)).toString();
  }

  private byte[] readBytesUntilEnd() throws IOException {
    return IOHelper.readInputStreamToByteArray(inputStream);
  }


  private byte[] readBytes(int byteCount) throws IOException {
    byte[] result = new byte[byteCount];
    int bytesRead = IOHelper.readFully(inputStream, result, 0, byteCount);
    if (bytesRead != byteCount) {
      return null;
    } else {
      return result;
    }
  }

}