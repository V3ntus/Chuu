package core.apis;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExecutorsSingleton {


	private static ExecutorService instance;

	private ExecutorsSingleton() {
	}


	public static ExecutorService getInstanceUsingDoubleLocking() {
		if (instance == null) {
			synchronized (ExecutorService.class) {
				if (instance == null) {
					instance = Executors.newFixedThreadPool(13);
				}
			}
		}
		return instance;
	}

}