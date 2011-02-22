// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.index;

import java.io.DataInput;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import org.galagosearch.core.retrieval.query.Node;
import org.galagosearch.core.retrieval.query.NodeType;
import org.galagosearch.core.retrieval.structured.CountIterator;
import org.galagosearch.core.retrieval.structured.DocumentOrderedCountIterator;
import org.galagosearch.core.retrieval.structured.DocumentOrderedIterator;
import org.galagosearch.core.retrieval.structured.ExtentIterator;
import org.galagosearch.core.util.ExtentArray;
import org.galagosearch.tupleflow.BufferedFileDataStream;
import org.galagosearch.tupleflow.DataStream;
import org.galagosearch.tupleflow.Utility;
import org.galagosearch.tupleflow.VByteInput;

/**
 * Reads a simple positions-based index, where each inverted list in the
 * index contains both term count information and term position information.
 * The term counts data is stored separately from term position information for
 * faster query processing when no positions are needed.
 * 
 * (12/16/2010, irmarc): In order to facilitate faster count-only processing,
 *                        the default iterator created will not even open the
 *                        positions list when iterating. This is an interesting
 *                        enough change that there are now two versions of the iterator
 *
 * @author trevor, irmarc
 */
public class PositionIndexReader extends KeyListReader implements AggregateReader {

  public class TermExtentIterator extends KeyListReader.ListIterator implements ExtentIterator {

    int documentCount;
    int totalPositionCount;
    VByteInput documents;
    VByteInput counts;
    VByteInput positions;
    int documentIndex;
    int currentDocument;
    int currentCount;
    ExtentArray extentArray;
    long startPosition, endPosition;
    RandomAccessFile input;
    // to support skipping
    VByteInput skips;
    VByteInput skipPositions;
    DataStream skipPositionsStream;
    DataStream documentsStream;
    DataStream countsStream;
    DataStream positionsStream;
    int skipDistance;
    int skipResetDistance;
    long numSkips;
    long skipsRead;
    long nextSkipDocument;
    long lastSkipPosition;
    long documentsByteFloor;
    long countsByteFloor;
    long positionsByteFloor;

    TermExtentIterator(GenericIndexReader.Iterator iterator) throws IOException {
      super(iterator);
    }

    // Initialization method.
    //
    // Even though we check for skips multiple times, in terms of how the data is loaded
    // its easier to do the parts when appropriate
    private void initialize() throws IOException {
      input.seek(startPosition);
      DataInput stream = new VByteInput(input);

      // metadata
      int options = stream.readInt();
      documentCount = stream.readInt();
      totalPositionCount = stream.readInt();
      if ((options & ValueIterator.HAS_SKIPS) == ValueIterator.HAS_SKIPS) {
        skipDistance = stream.readInt();
        skipResetDistance = stream.readInt();
        numSkips = stream.readLong();
      }

      // segment lengths
      long documentByteLength = stream.readLong();
      long countsByteLength = stream.readLong();
      long positionsByteLength = stream.readLong();
      long skipsByteLength = 0;
      long skipPositionsByteLength = 0;

      if ((options & DocumentOrderedIterator.HAS_SKIPS) == DocumentOrderedIterator.HAS_SKIPS) {
        skipsByteLength = stream.readLong();
        skipPositionsByteLength = stream.readLong();
      }

      long documentStart = input.getFilePointer();
      long documentEnd = documentStart + documentByteLength;

      long countsStart = documentEnd;
      long countsEnd = countsStart + countsByteLength;

      long positionsStart = countsEnd;
      long positionsEnd = positionsStart + positionsByteLength;


      if ((options & DocumentOrderedIterator.HAS_SKIPS) == DocumentOrderedIterator.HAS_SKIPS) {

        long skipsStart = positionsEnd;
        long skipsEnd = skipsStart + skipsByteLength;

        long skipPositionsStart = skipsEnd;
        long skipPositionsEnd = skipPositionsStart + skipPositionsByteLength;

        assert skipPositionsEnd == endPosition;

        // we do these here b/c of scoping issues w/ the variables above
        documentsStream = new BufferedFileDataStream(input, documentStart, documentEnd);
        documents = new VByteInput(documentsStream);
        countsStream = new BufferedFileDataStream(input, countsStart, countsEnd);
        counts = new VByteInput(countsStream);
        positionsStream = new BufferedFileDataStream(input, positionsStart, positionsEnd);
        positions = new VByteInput(positionsStream);
        skips = new VByteInput(new BufferedFileDataStream(input, skipsStart, skipsEnd));
        skipPositionsStream = new BufferedFileDataStream(input, skipPositionsStart,
                skipPositionsEnd);
        skipPositions = new VByteInput(skipPositionsStream);

        // load up
        nextSkipDocument = skips.readInt();
        documentsByteFloor = 0;
        countsByteFloor = 0;
        positionsByteFloor = 0;
      } else {
        assert positionsEnd == endPosition;
        skips = null;
        skipPositions = null;
        documents = new VByteInput(new BufferedFileDataStream(input, documentStart, documentEnd));
        counts = new VByteInput(new BufferedFileDataStream(input, countsStart, countsEnd));
        positions = new VByteInput(new BufferedFileDataStream(input, positionsStart, positionsEnd));

      }

      extentArray = new ExtentArray();
      documentIndex = 0;

      loadExtents();
    }

    // Loads up a single set of positions for a identifier. Basically it's the
    // load that needs to be done when moving forward one in the posting list.
    private void loadExtents() throws IOException {
      currentDocument += documents.readInt();
      currentCount = counts.readInt();
      extentArray.reset();

      int position = 0;
      for (int i = 0; i < currentCount; i++) {
        position += positions.readInt();
        extentArray.add(currentDocument, position, position + 1);
      }
    }

    public String getEntry() {
      StringBuilder builder = new StringBuilder();

      builder.append(getKey());
      builder.append(",");
      builder.append(currentDocument);
      for (int i = 0; i < extentArray.getPositionCount(); ++i) {
        builder.append(",");
        builder.append(extentArray.getBuffer()[i].begin);
      }

      return builder.toString();
    }

    public void reset(GenericIndexReader.Iterator iterator) throws IOException {
      key = iterator.getKey();
      dataLength = iterator.getValueLength();
      startPosition = iterator.getValueStart();
      endPosition = iterator.getValueEnd();

      input = iterator.getInput();
      reset();
    }

    public void reset() throws IOException {
      currentDocument = 0;
      currentCount = 0;
      extentArray.reset();

      initialize();
    }

    public boolean nextEntry() throws IOException {
      documentIndex = Math.min(documentIndex + 1, documentCount);
      if (!isDone()) {
        loadExtents();
        return true;
      }
      return false;
    }

    public boolean hasMatch(int document) {
      return (!isDone() && identifier() == document);
    }

    public void moveTo(int document) throws IOException {
      skipToEntry(document);
    }

    // If we have skips - it's go time
    @Override
    public boolean skipToEntry(int document) throws IOException {
      if (skips == null || document <= nextSkipDocument) {
        return super.skipToEntry(document);
      }

      // if we're here, we're skipping
      while (skipsRead < numSkips
              && document > nextSkipDocument) {
        skipOnce();
      }
      repositionMainStreams();
      return super.skipToEntry(document); // linear from here
    }

    // This only moves forward in tier 1, reads from tier 2 only when
    // needed to update floors
    //
    private void skipOnce() throws IOException {
      assert skipsRead < numSkips;
      long currentSkipPosition = lastSkipPosition + skips.readInt();

      if (skipsRead % skipResetDistance == 0) {
        // Position the skip positions stream
        skipPositionsStream.seek(currentSkipPosition);

        // now set the floor values
        documentsByteFloor = skipPositions.readInt();
        countsByteFloor = skipPositions.readInt();
        positionsByteFloor = skipPositions.readLong();
      }
      currentDocument = (int) nextSkipDocument;

      // May be at the end of the buffer
      if (skipsRead + 1 == numSkips) {
        nextSkipDocument = Integer.MAX_VALUE;
      } else {
        nextSkipDocument += skips.readInt();
      }
      skipsRead++;
      lastSkipPosition = currentSkipPosition;
    }

    private void repositionMainStreams() throws IOException {
      // If we just reset the floors, don't read the 2nd tier again
      if ((skipsRead - 1) % skipResetDistance == 0) {
        documentsStream.seek(documentsByteFloor);
        countsStream.seek(countsByteFloor);
        positionsStream.seek(positionsByteFloor);
      } else {
        skipPositionsStream.seek(lastSkipPosition);
        documentsStream.seek(documentsByteFloor + skipPositions.readInt());
        countsStream.seek(countsByteFloor + skipPositions.readInt());
        positionsStream.seek(positionsByteFloor + skipPositions.readLong());
      }
      documentIndex = (int) (skipDistance * skipsRead) - 1;
    }

    public boolean isDone() {
      return documentIndex >= documentCount;
    }

    public ExtentArray extents() {
      return extentArray;
    }

    public int identifier() {
      return currentDocument;
    }

    public int count() {
      return currentCount;
    }

    public long totalEntries() {
      return ((long) documentCount);
    }

    // TODO: Declare in an interface
    public int totalPositions() {
      return totalPositionCount;
    }

    public int compareTo(CountIterator other) {
      if (isDone() && !other.isDone()) {
        return 1;
      }
      if (other.isDone() && !isDone()) {
        return -1;
      }
      if (isDone() && other.isDone()) {
        return 0;
      }
      return identifier() - other.identifier();
    }
  }

  /**
   * This iterator simply ignores the positions information - faster b/c when incrementing or loading or skipping,
   * we don't have to bookkeep the positions buffer. Overall smaller footprint and faster execution.
   *
   */
  public class TermCountIterator extends KeyListReader.ListIterator implements ExtentIterator {

    int documentCount;
    int collectionCount;
    VByteInput documents;
    VByteInput counts;
    int documentIndex;
    int currentDocument;
    int currentCount;
    // Support for resets
    long startPosition, endPosition;
    RandomAccessFile input;
    // to support skipping
    VByteInput skips;
    VByteInput skipPositions;
    DataStream skipPositionsStream;
    DataStream documentsStream;
    DataStream countsStream;
    int skipDistance;
    int skipResetDistance;
    long numSkips;
    long skipsRead;
    long nextSkipDocument;
    long lastSkipPosition;
    long documentsByteFloor;
    long countsByteFloor;

    TermCountIterator(GenericIndexReader.Iterator iterator) throws IOException {
      super(iterator);
    }

    // Initialization method.
    //
    // Even though we check for skips multiple times, in terms of how the data is loaded
    // its easier to do the parts when appropriate
    private void initialize() throws IOException {
      input.seek(startPosition);
      DataInput stream = new VByteInput(input);

      // metadata
      int options = stream.readInt();
      documentCount = stream.readInt();
      collectionCount = stream.readInt();
      if ((options & DocumentOrderedIterator.HAS_SKIPS) == DocumentOrderedIterator.HAS_SKIPS) {
        skipDistance = stream.readInt();
        skipResetDistance = stream.readInt();
        numSkips = stream.readLong();
      }

      // segment lengths
      long documentByteLength = stream.readLong();
      long countsByteLength = stream.readLong();
      long positionsByteLength = stream.readLong();
      long skipsByteLength = 0;
      long skipPositionsByteLength = 0;

      if ((options & DocumentOrderedIterator.HAS_SKIPS) == DocumentOrderedIterator.HAS_SKIPS) {
        skipsByteLength = stream.readLong();
        skipPositionsByteLength = stream.readLong();
      }

      long documentStart = input.getFilePointer();
      long documentEnd = documentStart + documentByteLength;

      long countsStart = documentEnd;
      long countsEnd = countsStart + countsByteLength;

      // Still do this math to ensure correctness in the assertion below
      long positionsStart = countsEnd;
      long positionsEnd = positionsStart + positionsByteLength;


      if ((options & DocumentOrderedIterator.HAS_SKIPS) == DocumentOrderedIterator.HAS_SKIPS) {

        long skipsStart = positionsEnd;
        long skipsEnd = skipsStart + skipsByteLength;

        long skipPositionsStart = skipsEnd;
        long skipPositionsEnd = skipPositionsStart + skipPositionsByteLength;

        assert skipPositionsEnd == endPosition;

        // we do these here b/c of scoping issues w/ the variables above
        documentsStream = new BufferedFileDataStream(input, documentStart, documentEnd);
        documents = new VByteInput(documentsStream);
        countsStream = new BufferedFileDataStream(input, countsStart, countsEnd);
        counts = new VByteInput(countsStream);
        skips = new VByteInput(new BufferedFileDataStream(input, skipsStart, skipsEnd));
        skipPositionsStream = new BufferedFileDataStream(input, skipPositionsStart,
                skipPositionsEnd);
        skipPositions = new VByteInput(skipPositionsStream);

        // load up
        nextSkipDocument = skips.readInt();
        documentsByteFloor = 0;
        countsByteFloor = 0;
      } else {
        assert positionsEnd == endPosition;
        skips = null;
        skipPositions = null;
        documents = new VByteInput(new BufferedFileDataStream(input, documentStart, documentEnd));
        counts = new VByteInput(new BufferedFileDataStream(input, countsStart, countsEnd));
      }
      documentIndex = 0;

      load();
    }

    // Only loading the docid and the count
    private void load() throws IOException {
      currentDocument += documents.readInt();
      currentCount = counts.readInt();
    }

    public String getEntry() {
      StringBuilder builder = new StringBuilder();

      builder.append(getKey());
      builder.append(",");
      builder.append(currentDocument);
      builder.append(",");
      builder.append(currentCount);

      return builder.toString();
    }

    public void reset(GenericIndexReader.Iterator iterator) throws IOException {
      startPosition = iterator.getValueStart();
      endPosition = iterator.getValueEnd();
      dataLength = iterator.getValueLength();
      input = iterator.getInput();
      key = iterator.getKey();
      initialize();
    }

    public void reset() throws IOException {
      currentDocument = 0;
      currentCount = 0;
      initialize();
    }

    public boolean nextEntry() throws IOException {
      documentIndex = Math.min(documentIndex + 1, documentCount);
      if (!isDone()) {
        load();
        return true;
      }
      return false;
    }

    public boolean hasMatch(int document) {
      return (!isDone() && identifier() == document);
    }

    public void moveTo(int document) throws IOException {
      skipToEntry(document);
    }

    // If we have skips - it's go time
    @Override
    public boolean skipToEntry(int document) throws IOException {
      if (skips == null || document <= nextSkipDocument) {
        return super.skipToEntry(document);
      }

      // if we're here, we're skipping
      while (skipsRead < numSkips
              && document > nextSkipDocument) {
        skipOnce();
      }
      repositionMainStreams();
      return super.skipToEntry(document); // linear from here
    }

    // This only moves forward in tier 1, reads from tier 2 only when
    // needed to update floors
    //
    private void skipOnce() throws IOException {
      assert skipsRead < numSkips;
      long currentSkipPosition = lastSkipPosition + skips.readInt();

      if (skipsRead % skipResetDistance == 0) {
        // Position the skip positions stream
        skipPositionsStream.seek(currentSkipPosition);

        // now set the floor values
        documentsByteFloor = skipPositions.readInt();
        countsByteFloor = skipPositions.readInt();
        skipPositions.readLong(); // throw away, but we have to move it forward
      }
      currentDocument = (int) nextSkipDocument;

      // May be at the end of the buffer
      if (skipsRead + 1 == numSkips) {
        nextSkipDocument = Integer.MAX_VALUE;
      } else {
        nextSkipDocument += skips.readInt();
      }
      skipsRead++;
      lastSkipPosition = currentSkipPosition;
    }

    private void repositionMainStreams() throws IOException {
      // If we just reset the floors, don't read the 2nd tier again
      if ((skipsRead - 1) % skipResetDistance == 0) {
        documentsStream.seek(documentsByteFloor);
        countsStream.seek(countsByteFloor);
      } else {
        skipPositionsStream.seek(lastSkipPosition);
        documentsStream.seek(documentsByteFloor + skipPositions.readInt());
        countsStream.seek(countsByteFloor + skipPositions.readInt());
        // we seek here, so no reading needed
      }
      documentIndex = (int) (skipDistance * skipsRead) - 1;
    }

    public boolean isDone() {
      return documentIndex >= documentCount;
    }

    public ExtentArray extents() {
      throw new UnsupportedOperationException("Extents not supported in the TermCountIterator");
    }

    public int identifier() {
      return currentDocument;
    }

    public int count() {
      return currentCount;
    }

    public long totalEntries() {
      return documentCount;
    }

    // TODO: Declare in an interface
    public int totalPositions() {
      return collectionCount;
    }

    public int compareTo(CountIterator other) {
      if (isDone() && !other.isDone()) {
        return 1;
      }
      if (other.isDone() && !isDone()) {
        return -1;
      }
      if (isDone() && other.isDone()) {
        return 0;
      }
      return identifier() - other.identifier();
    }
  }
  GenericIndexReader reader;

  public PositionIndexReader(GenericIndexReader reader) throws IOException {
    super(reader);
  }

  public PositionIndexReader(String pathname) throws FileNotFoundException, IOException {
    super(pathname);
  }

  @Override
  public Iterator getIterator() throws IOException {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public ValueIterator getListIterator() throws IOException {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  /**
   * Returns an iterator pointing at the first term in the index.
   */
  public ExtentIterator getIterator() throws IOException {
    return new TermExtentIterator(reader.getIterator());
  }

  /**
   * Returns an iterator pointing at the specified term, or
   * null if the term doesn't exist in the inverted file.
   */
  public ExtentIterator getTermExtents(String term) throws IOException {
    GenericIndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));

    if (iterator != null) {
      return new TermExtentIterator(iterator);
    }
    return null;
  }

  public ExtentIterator getTermCounts(String term) throws IOException {
    GenericIndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));

    if (iterator != null) {
      return new TermCountIterator(iterator);
    }
    return null;
  }

  public void close() throws IOException {
    reader.close();
  }

  public Map<String, NodeType> getNodeTypes() {
    HashMap<String, NodeType> types = new HashMap<String, NodeType>();
    types.put("counts", new NodeType(TermCountIterator.class));
    types.put("extents", new NodeType(TermExtentIterator.class));

    return types;
  }

  public KeyIterator getIterator(Node node) throws IOException {
    if (node.getOperator().equals("counts")) {
      return getTermCounts(node.getDefaultParameter("term"));
    } else {
      return getTermExtents(node.getDefaultParameter("term"));
    }
  }

  // I add these in order to return identifier frequency and collection frequency
  // information for terms. Any other way from the iterators are SLOW
  // unless the headers have already been loaded.
  // We need a better interface for these.
  // TODO:: Clean abstraction for this
  public int documentCount(String term) throws IOException {
    GenericIndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));
    if (iterator == null) {
      return 0;
    }

    long startPosition = iterator.getValueStart();
    long endPosition = iterator.getValueEnd();

    RandomAccessFile input = iterator.getInput();
    input.seek(startPosition);
    DataInput stream = new VByteInput(input);

    // header information - have to read b/c it's compressed
    stream.readInt(); // skip option information
    int documentCount = stream.readInt();
    return documentCount;
  }

  // TODO: Clean abstraction for this
  public int termCount(String term) throws IOException {
    GenericIndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));
    if (iterator == null) {
      return 0;
    }
    long startPosition = iterator.getValueStart();
    long endPosition = iterator.getValueEnd();

    RandomAccessFile input = iterator.getInput();
    input.seek(startPosition);
    DataInput stream = new VByteInput(input);

    // Can't just seek b/c the numbers are compressed
    stream.readInt();
    stream.readInt();
    int totalPositionCount = stream.readInt();
    return totalPositionCount;
  }
}
