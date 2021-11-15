package com.paremus.deployment.framework.provider.impl;

import static com.paremus.deployment.framework.provider.ChildFrameworkEvent.EventType.DESTROYED;
import static com.paremus.deployment.framework.provider.ChildFrameworkEvent.EventType.DESTROYING;
import static com.paremus.deployment.framework.provider.ChildFrameworkEvent.EventType.INITIALIZED;
import static com.paremus.deployment.framework.provider.ChildFrameworkEvent.EventType.MOVED;
import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.deployment.framework.provider.ChildFrameworkEvent;
import com.paremus.deployment.framework.provider.ChildFrameworkListener;
import com.paremus.deployment.framework.provider.ChildFrameworkManager;

@Component(name="com.paremus.deployment.framework.provider")
public class FrameworkManagerImpl implements ChildFrameworkManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(FrameworkManagerImpl.class);
	
	private final ConcurrentMap<String, Framework> map = new ConcurrentHashMap<String, Framework>();

	private final List<ChildFrameworkListener> listeners = new CopyOnWriteArrayList<>();
	
	private final ReadWriteLock lock = new ReentrantReadWriteLock();
	
	private FrameworkFactory factory;
	
	private String defaultStorageParent;
	
	private Map<String, String> config;
	
	@Reference(policy=DYNAMIC, cardinality=MULTIPLE)
	public void addListener(ChildFrameworkListener listener) {
		lock.writeLock().lock();
		try {
			listeners.add(listener);
			
			for(Entry<String, Framework> e : map.entrySet()) {
				listener.childFrameworkEvent(new ChildFrameworkEvent(
						INITIALIZED, e.getKey(), e.getValue()));
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void removeListener(ChildFrameworkListener listener) {
		lock.writeLock().lock();
		try {
			listeners.remove(listener);
		} finally {
			lock.writeLock().unlock();
		}
	}
	
	@Activate
	public void activate(BundleContext context, Map<String, Object> map) {
		ServiceLoader<FrameworkFactory> loader = ServiceLoader.load(FrameworkFactory.class, 
				context.getBundle(0).adapt(BundleWiring.class).getClassLoader());
		
		lock.writeLock().lock();
		try {
			factory = loader.iterator().next();
			File f = context.getDataFile("child-frameworks");
			f.mkdirs();
			defaultStorageParent = f.getAbsolutePath();
			config = flatten(map);
		} finally {
			lock.writeLock().unlock();
		}
		
	}

	@Modified
	public void modified(BundleContext context, Map<String, Object> map) {
		LOGGER.debug("Updating configuration for the FrameworkManager. " +
				"Note that this will only apply to frameworks created after this update");
		lock.writeLock().lock();
		try {
			config = flatten(map);
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Deactivate
	public void deactivate() {
		LOGGER.debug("Deactivating all child frameworks because the component became unavailable");
		lock.writeLock().lock();
		try {
			factory = null;
		} finally {
			lock.writeLock().unlock();
		}

		for(Entry<String, Framework> e : map.entrySet()) {
			Framework f = e.getValue();
			try {
				f.stop();
			} catch (BundleException be) {
				LOGGER.error("There was a problem stopping the framework with id " + e.getKey(), be);
			}
			
			callListeners(new ChildFrameworkEvent(DESTROYED, e.getKey(), f));
		}
		map.clear();
	}
	
	private Map<String, String> flatten(Map<String, Object> map) {
		Map<String, String> toReturn = new HashMap<String, String>();
		
		toReturn.put(Constants.FRAMEWORK_STORAGE, defaultStorageParent);
		toReturn.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
		
		if(map != null) {
			for(Entry<String, Object> e : map.entrySet()) {
				toReturn.put(e.getKey(), String.valueOf(e.getValue()));
			}
		}
		return toReturn;
	}

	@Override
	public List<String> listFrameworkIds() {
		ArrayList<String> list = new ArrayList<>(map.keySet());
		Collections.sort(list);
		return list;
	}

	public Framework getFramework(String id) {
		return map.get(id);
	}

	public Framework createFramework(String id, Map<String, String> props) throws IllegalStateException, BundleException {
		Framework f = map.get(id);
		if(f != null) {
			throw new IllegalStateException("The framework " + id + "already exists");
		}
		
		lock.readLock().lock();
		try {
			if(factory == null) {
				throw new IllegalStateException("This service is shutting down");
			}

			Map<String, String> fwConfig = new HashMap<String, String>(config);
			fwConfig.put(Constants.FRAMEWORK_STORAGE, config.get(Constants.FRAMEWORK_STORAGE) + id);
			if(props != null) {
				fwConfig.putAll(props);
			}
			
			f = factory.newFramework(fwConfig);
			Framework tmp = map.putIfAbsent(id, f);
			if(tmp != null) {
				throw new IllegalStateException("The framework " + id + " already exists");
			}

			f.init();
			
			callListeners(new ChildFrameworkEvent(INITIALIZED, id, f));
		} finally {
			lock.readLock().unlock();
		}
		
		return f;
	}

	@Override
	public void dispose(String id) {
		Framework f = map.remove(id);
		if(f != null) {
			callListeners(new ChildFrameworkEvent(DESTROYING, id, f));
			lock.readLock().lock();
			try {
				String s = f.getBundleContext().getProperty(Constants.FRAMEWORK_STORAGE);
				try {
					f.stop();
					f.waitForStop(5000);
				} catch (Exception e) {
					LOGGER.error("There was a problem stopping the framework with id " + id);
				}
				
				callListeners(new ChildFrameworkEvent(DESTROYED, id, f));
				
				deleteStorageArea(s);
			} finally {
				lock.readLock().unlock();
			}
		}
	}

	private void callListeners(ChildFrameworkEvent cfe) {
		for(ChildFrameworkListener l : listeners) {
			try {
				l.childFrameworkEvent(cfe);
			} catch (Exception e) {
				LOGGER.error("An error occurred calling the framework listener", e);
			}
		}
	}

	private void deleteStorageArea(String s) {
		try {
			java.nio.file.Files.walkFileTree(new File(s).toPath(), new SimpleFileVisitor<Path>() {

				@Override
				public FileVisitResult visitFile(Path file,
						BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir,
						IOException exc) throws IOException {
					if(exc != null) throw exc;
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ioe) {
			LOGGER.error("Unable to delete the framework storage area " + s);
		}
	}

	@Override
	public Framework renameFramework(String currentId, String newId) {
		Framework f = map.get(currentId);

		if(f == null) {
			throw new IllegalStateException("The framework " + currentId + " does not exist");
		}
		lock.readLock().lock();
		try {
			Framework clash = map.putIfAbsent(newId, f);
			if(clash != null) {
				throw new IllegalStateException("The proposed new id " + newId + " for the framework " + currentId + 
						" is already taken.");
			} else {
				map.remove(currentId);
				callListeners(new ChildFrameworkEvent(MOVED, newId, f));
			}
		} finally {
			lock.readLock().unlock();
		}
		
		return f;
	}

}