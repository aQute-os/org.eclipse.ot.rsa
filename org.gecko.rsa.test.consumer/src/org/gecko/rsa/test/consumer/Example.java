package org.gecko.rsa.test.consumer;

import org.gecko.rsa.test.api.HelloWorld;
import org.osgi.service.component.annotations.*;

@Component(immediate = true)
public class Example {
	
	@Reference
//	@Reference(target = "(service.imported=true)")
	private HelloWorld hello;
	
	@Activate
	public void activate() {
		System.out.println("Activate HelloWorld-Consumer");
		String name = "Freeze";
		System.out.println("Calling HelloWorld-Service for Mr. " + name + ": ");
		System.out.println("Result: " + hello.sayHello(name));
	}
	
	@Deactivate
	public void deactivate() {
		System.out.println("De-activate HelloWorld-Consumer");
	}

	// TODO: class provided by template

}
