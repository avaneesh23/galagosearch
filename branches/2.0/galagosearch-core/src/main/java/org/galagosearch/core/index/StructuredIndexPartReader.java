// BSD License (http://www.galagosearch.org/license)

package org.galagosearch.core.index;

import java.io.IOException;
import java.util.Map;
import org.galagosearch.core.retrieval.query.Node;
import org.galagosearch.core.retrieval.query.NodeType;
import org.galagosearch.tupleflow.Parameters;

/**
 * A StructuredIndexPart is an object that can create StructuredIterators that
 * can be used in query processing.  StructuredIndex creates many StructuredIndexPartReaders
 * and uses them to supply iterators to StructuredRetrieval.
 * 
 * Usually a StructuredIndexPartReader uses an IndexReader to retrieve data from disk,
 * then adds its own special logic to decode that data.
 * 
 * @author trevor
 */
public interface StructuredIndexPartReader {
    /// Closes any underlying files used by this index part.
    public void close() throws IOException;
    /// Returns a list of node types that this index can provide.
    public Map<String, NodeType> getNodeTypes();

    public String getDefaultOperator();

    /// Returns an iterator over the keys of the index.
    public KeyIterator getIterator() throws IOException;
    /// Returns an iterator corresponding to a query node from a StructuredQuery.
    /// The type of iterator returned is assumed to be a value iterator (i.e. over one
    /// list in the index)
    public ValueIterator getIterator(Node node) throws IOException;

    public Parameters getManifest();
}
