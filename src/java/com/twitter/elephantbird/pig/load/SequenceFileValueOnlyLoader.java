package com.twitter.elephantbird.pig.load;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileRecordReader;
import org.apache.pig.FileInputLoadFunc;
import org.apache.pig.backend.BackendException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;

/**
 * This file is intended to load the Value part of a SequenceFile into a Pig schema 
 * and ignore the Key part . Use <a href="#SequenceFileKeyValueLoader">SequenceFileKeyValueLoader</a>  
 * if both the Key and the Value need to be loaded into the schema.</br>
 * Files suitable for this loader can be created in Pig using <a href="#SequenceFileStorage">SequenceFileStorage</a>
 * and of course by any Map Reduce program using SequenceFileOutputFormat.</br>
 * 
 * Use:</br>
 * When instantiated with no separator this load returns a single tuple with one field to pig,
 * containing the "Value".</br>
 * 
 * When instantiated with a separator, it assumes that the Value contains a number of fields
 * delimited by the separator.</br>
 * 
 *	Example for comma separated values:</br>
 *	Assume Input has the following format
 *	<pre>
 *	 -----------------
 *	 | Key | Value   |
 *	 -----------------
 *	 | 0   | a,b,c,d |
 *	 | 1   | w,x,y,z |
 *	 -----------------
 *	</pre>
 *	<pre> 
 *	register elephant-bird.jar;
 *	DEFINE SequenceFileLoader com.twitter.elephantbird.pig.load.KeyValueSequenceFileLoader(',');
 *	</pre>
 *	<pre>
 *	input  =  LOAD '$inputfile' USING SequenceFileLoader AS ( 
 *		ValueOne:chararray,
 *		ValueTwo:chararray,
 *		ValueThree:chararray,
 *		ValueFour:chararray,
 *	);
 * </pre>
 *	The first line of input would be (a,b,c,d)</br>
 *	The second line of input would be (w,x,y,z)</br>
 *	The Key values (0 and 1) are discarded
 *
 *  @see SequenceFileKeyValueLoader
 */

public class SequenceFileValueOnlyLoader extends FileInputLoadFunc {
  
  private SequenceFileRecordReader<Writable, Writable> reader;
 
  private Writable key;
  private Writable value;
  private ArrayList<Object> mProtoTuple = null;
  private String delimiter = null;
  
  protected static final Log LOG = LogFactory.getLog(SequenceFileValueOnlyLoader.class);
  protected TupleFactory mTupleFactory = TupleFactory.getInstance();
  protected SerializationFactory serializationFactory;

  protected byte keyType = DataType.UNKNOWN;
  protected byte valType = DataType.UNKNOWN;
    
  public SequenceFileValueOnlyLoader() {
    mProtoTuple = new ArrayList<Object>(2);
  }

  public SequenceFileValueOnlyLoader(String delimiter) {
	mProtoTuple = new ArrayList<Object>();  
	this.delimiter = delimiter;
  }
  
 
  protected void setKeyType(Class<?> keyClass) throws BackendException {
    this.keyType |= inferPigDataType(keyClass);
    if (keyType == DataType.ERROR) { 
      LOG.warn("Unable to translate key "+key.getClass()+" to a Pig datatype");
      throw new BackendException("Unable to translate "+key.getClass()+" to a Pig datatype");
    } 
  }
  
  protected void setValueType(Class<?> valueClass) throws BackendException {
    this.valType |= inferPigDataType(valueClass);
    if (keyType == DataType.ERROR) { 
      LOG.warn("Unable to translate key "+key.getClass()+" to a Pig datatype");
      throw new BackendException("Unable to translate "+key.getClass()+" to a Pig datatype");
    } 
  }
    
  protected byte inferPigDataType(Type t) {
    if (t == DataByteArray.class) return DataType.BYTEARRAY;
    else if (t == Text.class) return DataType.CHARARRAY;
    else if (t == IntWritable.class) return DataType.INTEGER;
    else if (t == LongWritable.class) return DataType.LONG;
    else if (t == FloatWritable.class) return DataType.FLOAT;
    else if (t == DoubleWritable.class) return DataType.DOUBLE;
    else if (t == BooleanWritable.class) return DataType.BOOLEAN;
    else if (t == ByteWritable.class) return DataType.BYTE;
    // not doing maps or other complex types for now
    else return DataType.ERROR;
  }
  
  protected Object translateWritableToPigDataType(Writable w, byte dataType) {
    switch(dataType) {
      case DataType.CHARARRAY: return ((Text) w).toString();
      case DataType.BYTEARRAY: return((DataByteArray) w).get();
      case DataType.INTEGER: return ((IntWritable) w).get();
      case DataType.LONG: return ((LongWritable) w).get();
      case DataType.FLOAT: return ((FloatWritable) w).get();
      case DataType.DOUBLE: return ((DoubleWritable) w).get();
      case DataType.BYTE: return ((ByteWritable) w).get();
    }
    
    return null;
  }
  
  @Override
  public Tuple getNext() throws IOException {
    boolean next = false;
    try {
      next = reader.nextKeyValue();
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
    
    if (!next) return null;
    
    key = reader.getCurrentKey();
    value = reader.getCurrentValue();
    
    if (keyType == DataType.UNKNOWN && key != null) {
        setKeyType(key.getClass());
    }
    if (valType == DataType.UNKNOWN && value != null) {
        setValueType(value.getClass());
    }
    
    // we can only break the value into pieces if it's a chararry 
    if (this.delimiter != null && valType == DataType.CHARARRAY) {
    	
    	String compositeValue = (String)translateWritableToPigDataType(value, valType);
    	String[] arrayValue = valueToArray(compositeValue);
    	
    	for (int i = 0; i < arrayValue.length; i++) {
    		mProtoTuple.add( arrayValue[i] );
    	}
    	
        Tuple t =  mTupleFactory.newTuple(mProtoTuple);
        mProtoTuple.clear();
        return t;
    }
    else {
    	mProtoTuple.add(translateWritableToPigDataType(key, keyType));
        mProtoTuple.add(translateWritableToPigDataType(value, valType));
        Tuple t =  mTupleFactory.newTuple(mProtoTuple);
        mProtoTuple.clear();
        return t;
    }
    
  }

  @SuppressWarnings("unchecked")
  @Override
  public InputFormat getInputFormat() throws IOException {
    return new SequenceFileInputFormat<Writable, Writable>();
  }

  @SuppressWarnings("unchecked")
  @Override
  public void prepareToRead(RecordReader reader, PigSplit split)
        throws IOException {
    this.reader = (SequenceFileRecordReader) reader;
  }

  @Override
  public void setLocation(String location, Job job) throws IOException {
    FileInputFormat.setInputPaths(job, location);    
  }
  
  private String[] valueToArray (String value) {
	  
	  String[] result = value.split(this.delimiter);

	  for (int i = 0; i < result.length; i++) {
			if (result[i].equals(""))
				result[i] = null;
		}
	  
	  return result;
  }
}
