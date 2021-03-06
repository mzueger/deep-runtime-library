/*
 * Copyright 2011 - 2013 NTB University of Applied Sciences in Technology
 * Buchs, Switzerland, http://www.ntb.ch/inf
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 *   
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package ch.ntb.inf.deep.runtime.mpc555.driver;

import java.io.IOException;

import ch.ntb.inf.deep.runtime.mpc555.Interrupt;
import ch.ntb.inf.deep.runtime.mpc555.Kernel;
import ch.ntb.inf.deep.runtime.util.ByteFifo;
import ch.ntb.inf.deep.unsafe.US;

/**
 * <p>Interrupt controlled driver for the <i>Serial Communication Interface 1</i>
 * of the Freescale MPC555.</p>
 * <p><b>Remember:</b><br>
 * Depending on the baudrate configured, the effective baudrate can be different.
 * This may cause miss interpretation of the bytes sent at the receiver! For more
 * details, please consider table 14-29 in chapter 14.8.7.3 in the <a href=
 * "http://www.ntb.ch/infoportal/_media/embedded_systems:mpc555:mpc555_usermanual.pdf"
 * >MPC555 User's manual</a>.
 * </p>
 */
/* Changes:
 * 3.6.2014		Urs Graf			exception handling added
 * 13.10.2011	NTB/Martin Zueger	reset() implemented, JavaDoc fixed
 * 08.03.2011	NTB/Urs Graf		ported to deep
 */
public class SCI1 extends Interrupt {

	public static SCIOutputStream out;
	public static SCIInputStream in;
	private static int d = 0;
	
	public static final byte NO_PARITY = 0, ODD_PARITY = 1, EVEN_PARITY = 2;

	// Driver states
	public static final int PORT_OPEN = 9, TX_EMPTY = 8, TX_COMPLETE = 7,
			RX_RDY = 6, RX_ACTIVE = 5;

	// Error states
	public static final int IDLE_LINE_DET = 4, OVERRUN_ERR = 3, NOISE_ERR = 2,
			FRAME_ERR = 1, PARITY_ERR = 0, LENGTH_NEG_ERR = -1,
			OFFSET_NEG_ERR = -2, NULL_POINTER_ERR = -3;
	public static final int QUEUE_LEN = 2047;
	public static final int CLOCK = Kernel.clockFrequency;
	private static Interrupt rxInterrupt, txInterrupt;

	private static short portStat; // just for saving flag portOpen
	private static short scc1r1; // content of SCC1R1

	private static int currentBaudRate = 9600;
	private static short currentParity = NO_PARITY;
	private static short currentDataBits = 8;
	
	/*
	 * rxQueue: the receive queue, head points to the front item, tail to tail
	 * item plus 1: head=tail -> empty q head is moved by the interrupt proc
	 */
	private static ByteFifo rxQueue;

	/*
	 * txQueue: the transmit queue, head points to the front item, tail to tail
	 * item plus 1: head=tail -> empty q head is moved by the interrupt proc,
	 * tail is moved by the send primitives called by the application
	 */
	private static ByteFifo txQueue;
	private static boolean txDone;

	@SuppressWarnings("unused")
	private static int intCtr;

	/* (non-Javadoc)
	 * @see ch.ntb.inf.deep.runtime.mpc555.Interrupt#action()
	 */
	public void action() {
		intCtr++;
		if (this == rxInterrupt) {
			short word = US.GET2(QSMCM.SC1DR);
			rxQueue.enqueue((byte) word);
		} else {
			if (txQueue.availToRead() > 0) {
				try {
					d = txQueue.dequeue();
				} catch (IOException e) {}
				US.PUT2(QSMCM.SC1DR, d);
			} else {
				txDone = true;
				scc1r1 &= ~(1 << QSMCM.scc1r1TIE);
				US.PUT2(QSMCM.SCC1R1, scc1r1);
			}
		}
	}

	private static void startTransmission() {
		if (txDone && (txQueue.availToRead() > 0)) {
			txDone = false;
			try {
				US.PUT2(QSMCM.SC1DR, txQueue.dequeue());
			} catch (IOException e) {}
			scc1r1 |= (1 << QSMCM.scc1r1TIE);
			US.PUT2(QSMCM.SCC1R1, scc1r1);
		}
	}

	/**
	 * Clear the receive buffer.
	 */
	public static void clearReceiveBuffer() {
		rxQueue.clear();
	}

	/**
	 * Clear the transmit buffer.
	 */
	public static void clearTransmitBuffer() {
		scc1r1 &= ~(1 << QSMCM.scc1r1TIE);
		US.PUT2(QSMCM.SCC1R1, scc1r1);
		txQueue.clear();
		txDone = true;
	}
	
	/**
	 * Clear the receive and transmit buffers.
	 */
	public static void clear() {
		clearReceiveBuffer();
		clearTransmitBuffer();
	}

	/**
	 * Stop the <i>Serial Communication Interface 1</i>.
	 */
	public static void stop() {
		clear();
		US.PUT2(QSMCM.SCC1R1, 0);
		portStat = 0;
	}

	/**
	 * <p>Initialize and start the <i>Serial Communication Interface 1</i>.</p>
	 * <p>This method have to be called before using the SCI1! The number of
	 * stop bits can't be set. There is always one stop bit!<p>
	 * 
	 * @param baudRate
	 *            The baud rate. Allowed Range: 64 to 500'000 bits/s.
	 * @param parity
	 *            Parity bits configuration. Possible values: {@link #NO_PARITY},
	 *            {@link #ODD_PARITY} or {@link #EVEN_PARITY}.
	 * @param data
	 *            Number of data bits. Allowed values are 7 to 9 bits. If you
	 *            choose 9 data bits, than is no parity bit more available!
	 */
	public static void start(int baudRate, short parity, short data) {
		stop();
		currentBaudRate = baudRate;
		currentParity = parity;
		currentDataBits = data;
		short scbr = (short) ((CLOCK / baudRate + 16) / 32);
		if (scbr <= 0)
			scbr = 1;
		else if (scbr > 8191)
			scbr = 8191;
		scc1r1 |= (1 << QSMCM.scc1r1TE) | (1 << QSMCM.scc1r1RE)
				| (1 << QSMCM.scc1r1RIE); // Transmitter and Receiver enable
		if (parity == 0) {
			if (data >= 9)
				scc1r1 |= (1 << QSMCM.scc1r1M);
		} else {
			if (data >= 8)
				scc1r1 |= (1 << QSMCM.scc1r1M) | (1 << QSMCM.scc1r1PE);
			else
				scc1r1 = (1 << QSMCM.scc1r1PE);
			if (parity == 1)
				scc1r1 |= (1 << QSMCM.scc1r1PT);
		}
		US.PUT2(QSMCM.SCC1R0, scbr);
		US.PUT2(QSMCM.SCC1R1, scc1r1);
		portStat |= (1 << PORT_OPEN);
		US.GET2(QSMCM.SC1SR); // Clear status register
	}

	/**
	 * Check the port status. Returns the port status bits.<br>
	 * Every bit is representing a flag (e.g. {@link #PORT_OPEN}).
	 * 
	 * @return the port status bits.
	 */
	public static short portStatus() {
		return (short) (portStat | US.GET2(QSMCM.SC1SR));
	}

	/**
	 * Returns the number of bytes available in the receive buffer.
	 * 
	 * @return number of bytes in the receive buffer.
	 */
	public static int availToRead() {
		return rxQueue.availToRead();
	}

	/**
	 * Returns the number of free bytes available in the transmit buffer.
	 * It is possible, to send the returned number of bytes in one
	 * nonblocking transfer.
	 * 
	 * @return the available free bytes in the transmit buffer.
	 */
	public static int availToWrite() {
		return txQueue.availToWrite();
	}

	/**
	 * Reads the given number of bytes from the SCI1. A call of
	 * this method is not blocking!
	 * 
	 * @param buffer
	 *            Byte aray to write the received data.
	 * @param off
	 *            Offset in the array to start writing the data.
	 * @param count
	 *            Length (number of bytes) to read.
	 * @return the number of bytes read.
	 * @throws IOException
	 *            if an error occurs while reading from this stream.
	 * @throws NullPointerException
	 *            if {@code buffer} is null.
	 * @throws IndexOutOfBoundsException
	 *            if {@code off < 0} or {@code count < 0}, or if
	 *            {@code off + count} is bigger than the length of
	 *            {@code buffer}.
	 */
	public static int read(byte[] buffer, int off, int count) throws IOException {
	   	int len = buffer.length;
        if ((off | count) < 0 || off > len || len - off < count) {
        	throw new ArrayIndexOutOfBoundsException(len, off, count);
        }
		for (int i = 0; i < count; i++) {
			buffer[off + i] = rxQueue.dequeue();
		}
		return len;
	}

	/**
	 * Reads the given number of bytes from the SCI1. A call of
	 * this method is not blocking!
	 * 
	 * @param buffer
	 *            Byte array to write the received data.
	 * @return the number of bytes read. 
	 * @throws IOException 
	 *            if no data available.
	 */
	public static int read(byte[] buffer) throws IOException {
		return read(buffer, 0, buffer.length);
	}

	/**
	 * Reads one byte from the SCI1. A call of
	 * this method is not blocking!
	 * 
	 * @return byte read or {@link ch.ntb.inf.deep.runtime.util.ByteFifo#NO_DATA} if
	 *             no data was available.
	 * @throws IOException 
	 *            if no byte available.
	 */
	public static int read() throws IOException {
		return rxQueue.dequeue();
	}

	/**
	 * Writes a given number of bytes into the transmit buffer.
	 * A call of this method is not blocking! There will only as
	 * many bytes written, which are free in the buffer.
	 * 
	 * @param buffer
	 *            Array of bytes to send.
	 * @param off
	 *            Offset to the data which should be sent.
	 * @param count
	 *            Number of bytes to send.
	 * @return the number of bytes written.
	 * @throws IOException
	 *            if an error occurs while writing to this stream.
	 * @throws NullPointerException
	 *            if {@code buffer} is null.
	 * @throws IndexOutOfBoundsException
	 *            if {@code off < 0} or {@code count < 0}, or if
	 *            {@code off + count} is bigger than the length of
	 *            {@code buffer}.
	 */
	public static int write(byte[] buffer, int off, int count) throws IOException{
		if ((portStat & (1 << PORT_OPEN)) == 0) throw new IOException("IOException");
    	int len = buffer.length;
        if ((off | count) < 0 || off > len || len - off < count) {
        	throw new ArrayIndexOutOfBoundsException(len, off, count);
        }
		for (int i = 0; i < count; i++) {
			txQueue.enqueue(buffer[off + i]);
		}
		startTransmission();
		return count;
	}

	/**
	 * Writes a given number of bytes into the transmit buffer.
	 * A call of this method is not blocking! There will only as
	 * many bytes written, which are free in the buffer.
	 * 
	 * @param buffer
	 *            Array of bytes to send.
	 * @return the number of bytes written.
	 * @throws IOException 
	 *            if an error occurs while writing to this stream.
	 */
	public static int write(byte[] buffer) throws IOException {
		return write(buffer, 0, buffer.length);
	}

	/**
	 * Writes a given byte into the transmit buffer.
	 * A call of this method is blocking! That means
	 * this method won't terminate until the byte is
	 * written to the buffer!
	 * 
	 * @param b
	 *            Byte to write.
	 * @throws IOException 
	 *            if an error occurs while writing to this stream.
	 */
	public static void write(byte b) throws IOException {
		if ((portStat & (1 << PORT_OPEN)) == 0) throw new IOException("IOException");
		while (txQueue.availToWrite() <= 0);
		txQueue.enqueue(b);
		startTransmission();
	}

	/**
	 * Resets the SCI1. This means, the SCI will be
	 * stopped and reinitialized with the same configuration.
	 */
	public static void reset() {
		stop();
		start(currentBaudRate, currentParity, currentDataBits);
	}
	
	static {
		out = new SCIOutputStream(SCIOutputStream.pSCI1);
		in = new SCIInputStream(SCIInputStream.pSCI1);
		QSMCM.init();

		rxQueue = new ByteFifo(QUEUE_LEN);
		txQueue = new ByteFifo(QUEUE_LEN);

		rxInterrupt = new SCI1();
		rxInterrupt.enableRegAdr = QSMCM.SCC1R1;
		rxInterrupt.enBit = QSMCM.scc1r1RIE;
		rxInterrupt.flagRegAdr = QSMCM.SC1SR;
		rxInterrupt.flag = QSMCM.sc1srRDRF;

		txInterrupt = new SCI1();
		txInterrupt.enableRegAdr = QSMCM.SCC1R1;
		txInterrupt.enBit = QSMCM.scc1r1TIE;
		txInterrupt.flagRegAdr = QSMCM.SC1SR;
		txInterrupt.flag = QSMCM.sc1srTDRE;

		Interrupt.install(rxInterrupt, 5, true);	
		Interrupt.install(txInterrupt, 5, true);	
	}
}