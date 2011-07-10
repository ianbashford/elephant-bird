package com.twitter.elephantbird.pig.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.pig.PigException;
import org.apache.pig.StoreFunc;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;

/**
 * This code originates in the storefunc example on the pig wiki 
 * <a href="http://pig.apache.org/docs/r0.8.1/udf.html#Load%2FStore+Functions">udf.html</a> with modifications
 * to write to a sequence file.</br>
 * Design.</br>
 * Sequence files need to be Key:Value stores so the "Key" is the record number processed in this task,
 * starting at zero. </br>
 * The schema is written into the "Value" using the separator specified or tab  &bsol;t by default.</br>
 * Note that the compression settings must be specified at runtime as in the example below (or via equivalent methods).</br>
 * 
 * The following example assumes use of hadoop-lzo (see <a href="https://github.com/kevinweil/hadoop-lzo">hadoop-lzo</a>)
 * but any compression algorithm work with sequence files...</br>
 * 
 * In the pig script:
 *  <pre>
 *	set    mapred.output.compression.type BLOCK
 *	set    mapred.output.compress true
 *	set    mapred.output.compression.codec com.hadoop.compression.lzo.LzoCodec
 *	</pre>
 *   
 *  Then make sure the jar file is registered and prepare some aliases:
 *  <pre> 
 *  register elephant-bird.jar;
 *  DEFINE CTRLASequenceFileStorage com.twitter.elephantbird.pig.store.SequenceFileStorage('&bsol;u0001');
 *  </pre>
 *   
 *  Example STORE lines. The first uses the alias defined above, the second defines on the fly (more like PigStorage): 
 *  <pre> 
 *   STORE &lt;alias&gt; into '$OUTPUT' using CTRLASequenceFileStorage;
 *   STORE &lt;alias&gt; into '$OUTPUT' using com.twitter.elephantbird.pig.store.SequenceFileStorage(',');
 *  </pre> 
 *  
 *  Two accompanying classes exist for reading in SequenceFiles:
 *   
 *   @see com.twitter.elephantbird.pig.load.SequenceFileKeyValueLoader 
 *   @see com.twitter.elephantbird.pig.load.SequenceFileValueOnlyLoader
 *   
 * 
 */


public class SequenceFileStorage extends StoreFunc {

    protected RecordWriter<WritableComparable, Text> writer = null;

    private byte fieldDel = '\t';
    private static final int BUFFER_SIZE = 1024;
    private static final String UTF8 = "UTF-8";
    private int lineNumber = 0;
    
    public SequenceFileStorage() {
    }

    public SequenceFileStorage(String delimiter) {
        this();
        if (delimiter.length() == 1) {
            this.fieldDel = (byte)delimiter.charAt(0);
        } else if (delimiter.length() > 1 && delimiter.charAt(0) == '\\') {
            switch (delimiter.charAt(1)) {
            case 't':
                this.fieldDel = (byte)'\t';
                break;

            case 'x':
               fieldDel =
                    (byte)Integer.parseInt(delimiter.substring(2), 16);
               break;
            case 'u':
                this.fieldDel =
                    (byte)Integer.parseInt(delimiter.substring(2));
                break;

            default:
                throw new RuntimeException("Unknown delimiter " + delimiter);
            }
        } else {
            throw new RuntimeException("PigStorage delimiter must be a single character");
        }
    }

    ByteArrayOutputStream mOut = new ByteArrayOutputStream(BUFFER_SIZE);

    @Override
    public void putNext(Tuple f) throws IOException {
        int sz = f.size();
        for (int i = 0; i < sz; i++) {
            Object field;
            try {
                field = f.get(i);
            } catch (ExecException ee) {
                throw ee;
            }

            putField(field);

            if (i != sz - 1) {
                mOut.write(fieldDel);
            }
        }
        Text text = new Text(mOut.toByteArray());
        try {
            this.writer.write(new LongWritable(this.lineNumber), text);
            mOut.reset();
            this.lineNumber++;
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void putField(Object field) throws IOException {
        //string constants for each delimiter
        String tupleBeginDelim = "(";
        String tupleEndDelim = ")";
        String bagBeginDelim = "{";
        String bagEndDelim = "}";
        String mapBeginDelim = "[";
        String mapEndDelim = "]";
        String fieldDelim = ",";
        String mapKeyValueDelim = "#";

        switch (DataType.findType(field)) {
        case DataType.NULL:
            break; // just leave it empty

        case DataType.BOOLEAN:
            mOut.write(((Boolean)field).toString().getBytes());
            break;

        case DataType.INTEGER:
            mOut.write(((Integer)field).toString().getBytes());
            break;

        case DataType.LONG:
            mOut.write(((Long)field).toString().getBytes());
            break;

        case DataType.FLOAT:
            mOut.write(((Float)field).toString().getBytes());
            break;

        case DataType.DOUBLE:
            mOut.write(((Double)field).toString().getBytes());
            break;

        case DataType.BYTEARRAY: {
            byte[] b = ((DataByteArray)field).get();
            mOut.write(b, 0, b.length);
            break;
                                 }

        case DataType.CHARARRAY:
            // oddly enough, writeBytes writes a string
            mOut.write(((String)field).getBytes(UTF8));
            break;

        case DataType.MAP:
            boolean mapHasNext = false;
            Map<String, Object> m = (Map<String, Object>)field;
            mOut.write(mapBeginDelim.getBytes(UTF8));
            for(Map.Entry<String, Object> e: m.entrySet()) {
                if(mapHasNext) {
                    mOut.write(fieldDelim.getBytes(UTF8));
                } else {
                    mapHasNext = true;
                }
                putField(e.getKey());
                mOut.write(mapKeyValueDelim.getBytes(UTF8));
                putField(e.getValue());
            }
            mOut.write(mapEndDelim.getBytes(UTF8));
            break;

        case DataType.TUPLE:
            boolean tupleHasNext = false;
            Tuple t = (Tuple)field;
            mOut.write(tupleBeginDelim.getBytes(UTF8));
            for(int i = 0; i < t.size(); ++i) {
                if(tupleHasNext) {
                    mOut.write(fieldDelim.getBytes(UTF8));
                } else {
                    tupleHasNext = true;
                }
                try {
                    putField(t.get(i));
                } catch (ExecException ee) {
                    throw ee;
                }
            }
            mOut.write(tupleEndDelim.getBytes(UTF8));
            break;

        case DataType.BAG:
            boolean bagHasNext = false;
            mOut.write(bagBeginDelim.getBytes(UTF8));
            Iterator<Tuple> tupleIter = ((DataBag)field).iterator();
            while(tupleIter.hasNext()) {
                if(bagHasNext) {
                    mOut.write(fieldDelim.getBytes(UTF8));
                } else {
                    bagHasNext = true;
                }
                putField((Object)tupleIter.next());
            }
            mOut.write(bagEndDelim.getBytes(UTF8));
            break;

        default: {
            int errCode = 2108;
            String msg = "Could not determine data type of field: " + field;
            throw new ExecException(msg, errCode, PigException.BUG);
        }

        }
    }

    @Override
    public OutputFormat getOutputFormat() {
        return new SequenceFileOutputFormat();
    }

    @Override
    public void prepareToWrite(RecordWriter writer) {
    	this.writer = writer;
    }

    @Override
    public void setStoreLocation(String location, Job job) throws IOException {
        job.getConfiguration().set("mapred.textoutputformat.separator", "");
        SequenceFileOutputFormat.setOutputPath(job, new Path(location));
    }


}
