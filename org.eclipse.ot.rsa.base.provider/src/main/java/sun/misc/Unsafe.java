/*
 * This type exists to mirror the publicly visible methods of sun.misc.Unsafe from the JDK.
 *
 * It provides no implementation, nor is it packaged in the resulting jar, it exists purely
 * so that this project can be compiled with versions of Java that do not contain the Unsafe
 * class on the compilation classpath.
 */
package sun.misc;

import java.lang.reflect.Field;

public class Unsafe {

	public void throwException(Throwable thrown) {
		// TODO Auto-generated method stub

	}

	public <T> T allocateInstance(Class<T> clazz) {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean getBoolean(Object pojo, long offset) {
		// TODO Auto-generated method stub
		return false;
	}

	public void putBoolean(Object pojo, long offset, boolean object) {
		// TODO Auto-generated method stub

	}

	public Object getObject(Object pojo, long offset) {
		// TODO Auto-generated method stub
		return null;
	}

	public void putObject(Object pojo, long offset, Object value) {
		// TODO Auto-generated method stub

	}

	public double getDouble(Object pojo, long offset) {
		// TODO Auto-generated method stub
		return 0.0;
	}

	public void putDouble(Object pojo, long offset, double d) {
		// TODO Auto-generated method stub

	}

	public byte getByte(Object pojo, long offset) {
		return 0;
	}

	public void putByte(Object pojo, long offset, byte object) {
		// TODO Auto-generated method stub

	}

	public char getChar(Object pojo, long offset) {
		// TODO Auto-generated method stub
		return 0;
	}

	public void putChar(Object pojo, long offset, char value) {
		// TODO Auto-generated method stub

	}

	public short getShort(Object pojo, long offset) {
		// TODO Auto-generated method stub
		return 0;
	}

	public void putShort(Object pojo, long offset, short value) {
		// TODO Auto-generated method stub

	}

	public int getInt(Object pojo, long offset) {
		// TODO Auto-generated method stub
		return 0;
	}

	public void putInt(Object pojo, long offset, int value) {
		// TODO Auto-generated method stub

	}

	public float getFloat(Object pojo, long offset) {
		// TODO Auto-generated method stub
		return 0;
	}

	public long objectFieldOffset(Field field) {
		// TODO Auto-generated method stub
		return 0;
	}

	public void putFloat(Object pojo, long offset, float value) {
		// TODO Auto-generated method stub

	}

	public long getLong(Object pojo, long offset) {
		// TODO Auto-generated method stub
		return -1;
	}

	public void putLong(Object pojo, long offset, long value) {
		// TODO Auto-generated method stub

	}

}
