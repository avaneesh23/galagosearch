// BSD License (http://www.galagosearch.org/license)

package org.galagosearch.core.parse;

import org.galagosearch.tupleflow.InputClass;
import org.galagosearch.tupleflow.OutputClass;
import org.galagosearch.tupleflow.StandardStep;
import org.galagosearch.tupleflow.TupleFlowParameters;
import org.galagosearch.tupleflow.TypeReader;
import org.galagosearch.tupleflow.Utility;
import org.galagosearch.tupleflow.execution.ErrorHandler;
import org.galagosearch.tupleflow.execution.Verification;
import org.galagosearch.core.types.DocumentWordPosition;
import org.galagosearch.core.types.NumberWordPosition;
import java.io.IOException;
import java.util.HashMap;
import org.galagosearch.core.types.NumberedDocumentData;

/**
 *
 * @author sjh
 */
@InputClass(className = "org.galagosearch.core.types.DocumentWordPosition")
@OutputClass(className = "org.galagosearch.core.types.NumberWordPosition")
public class PositionPostingsNumberer extends StandardStep<DocumentWordPosition, NumberWordPosition>
        implements DocumentWordPosition.Processor, NumberWordPosition.Source {

    TypeReader<NumberedDocumentData> reader;
    NumberedDocumentData currentNDD;
    
    public void process(DocumentWordPosition object) throws IOException {
      assert Utility.compare(currentNDD.identifier, object.document) <= 0 : 
        "PositionPostingNumberer is getting postings in the wrong order somehow.";
      
      while((currentNDD != null) &&
          (Utility.compare(currentNDD.identifier, object.document) < 0 )){
        currentNDD = reader.read();
      }
      
      if((currentNDD != null) &&
        (Utility.compare(currentNDD.identifier, object.document) == 0)){
        processor.process(
            new NumberWordPosition(currentNDD.number,
                object.word,
                object.position));
      } else {
        throw new IOException("Ran out of Document Numbers or Found Unknown Document");
      }
      
    }
    
    public PositionPostingsNumberer(TupleFlowParameters parameters) throws IOException {
      reader = parameters.getTypeReader("numberedDocumentData");
      currentNDD = reader.read();
    }

    public Class<DocumentWordPosition> getInputClass() {
        return DocumentWordPosition.class;
    }

    public Class<NumberWordPosition> getOutputClass() {
        return NumberWordPosition.class;
    }

    public static String getInputClass(TupleFlowParameters parameters) {
        return DocumentWordPosition.class.getName();
    }

    public static String getOutputClass(TupleFlowParameters parameters) {
        return NumberWordPosition.class.getName();
    }

    public static void verify(TupleFlowParameters parameters, ErrorHandler handler) {
        Verification.verifyTypeReader("numberedDocumentData", NumberedDocumentData.class,
                parameters, handler);
    }
}
