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

package ch.ntb.inf.deep.runtime.mpc555.test;

import java.io.PrintStream;

import ch.ntb.inf.deep.runtime.mpc555.driver.SCI2;
import ch.ntb.inf.deep.runtime.ppc32.Task;
import ch.ntb.inf.deep.runtime.util.Actionable;

public class ActionableTest2 extends Task {
	public void action() {
		System.out.println(ActionableImpl.count);
		if (nofActivations == 5) Task.remove(this);
	}
	
	static {
		SCI2.start(9600, SCI2.NO_PARITY, (short)8);
		System.out = new PrintStream(SCI2.out);
		System.out.println("Actionable test");
		new ActionableImpl(2);
		System.out.println(ActionableImpl.count);
		Task t = new ActionableTest2();
		t.period = 1000;
		Task.install(t);
	}
}



class ActionableImpl implements Actionable {
	static int count;
	static Task t;
	
	public void action() {
		count++;
		if (t.nofActivations == 50) Task.remove(t);
	}

	public ActionableImpl(int x) {
		count = x;
		t = new Task(this);
		Task.install(t);
	}
}
