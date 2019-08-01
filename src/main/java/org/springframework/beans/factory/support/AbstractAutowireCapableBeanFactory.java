/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.asm.commons.AdviceAdapter;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @since 13.02.2004
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/** Strategy for creating bean instances */
	private InstantiationStrategy instantiationStrategy = new CglibSubclassingInstantiationStrategy();

	/** Resolver strategy for method parameter names */
	private ParameterNameDiscoverer parameterNameDiscoverer = new LocalVariableTableParameterNameDiscoverer();

	/** Whether to automatically try to resolve circular references between beans */
	private boolean allowCircularReferences = true;

	/**
	 * Whether to resort to injecting a raw bean instance in case of circular reference,
	 * even if the injected bean eventually got wrapped.
	 */
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 */
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<Class<?>>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 */
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<Class<?>>();

	/** Cache of unfinished FactoryBean instances: FactoryBean name --> BeanWrapper */
	private final Map<String, BeanWrapper> factoryBeanInstanceCache =
			new ConcurrentHashMap<String, BeanWrapper>(16);

	/** Cache of filtered PropertyDescriptors: bean Class -> PropertyDescriptor array */
	private final Map<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<Class<?>, PropertyDescriptor[]>(64);


	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 */
	public AbstractAutowireCapableBeanFactory(BeanFactory parentBeanFactory) {
		this();
		setParentBeanFactory(parentBeanFactory);
	}


	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 */
	protected InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>The default is {@link LocalVariableTableParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	protected ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 * @see #setAllowCircularReferences
	 */
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 */
	public void ignoreDependencyInterface(Class<?> ifc) {
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory) {
			AbstractAutowireCapableBeanFactory otherAutowireFactory =
					(AbstractAutowireCapableBeanFactory) otherFactory;
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}


	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use prototype bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass);
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = false;
		return (T) createBean(beanClass.getName(), bd, null);
	}

	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		bd.allowCaching = false;
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition) {
			RootBeanDefinition rbd = (RootBeanDefinition) mbd;
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (!mbd.isPrototype()) {
			if (bd == null) {
				bd = new RootBeanDefinition(mbd);
			}
			bd.setScope(BeanDefinition.SCOPE_PROTOTYPE);
			bd.allowCaching = false;
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(beanName, bd, bw);
		return initializeBean(beanName, existingBean, bd);
	}

	public Object resolveDependency(DependencyDescriptor descriptor, String beanName) throws BeansException {
		return resolveDependency(descriptor, beanName, null, null);
	}


	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		final RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		}
		else {
			Object bean;
			final BeanFactory parent = this;
			if (System.getSecurityManager() != null) {
				bean = AccessController.doPrivileged(new PrivilegedAction<Object>() {
					public Object run() {
						return getInstantiationStrategy().instantiate(bd, null, parent);
					}
				}, getAccessControlContext());
			}
			else {
				bean = getInstantiationStrategy().instantiate(bd, null, parent);
			}
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	//调用BeanPostProcessor后置处理器实例对象初始化之后的处理方法  
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;
		//遍历容器为所创建的Bean添加的所有BeanPostProcessor后置处理器
		for (BeanPostProcessor beanProcessor : getBeanPostProcessors()) {
			//调用Bean实例所有的后置处理中的初始化后处理方法，为Bean实例对象在  
	        //初始化之后做一些自定义的处理操作
			result = beanProcessor.postProcessBeforeInitialization(result, beanName);
			if (result == null) {
				return result;
			}
		}
		return result;
	}

	//调用BeanPostProcessor后置处理器实例对象初始化之前的处理方法  
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {
		
		Object result = existingBean;
		//遍历容器为所创建的Bean添加的所有BeanPostProcessor后置处理器
		for (BeanPostProcessor beanProcessor : getBeanPostProcessors()) {
			//调用Bean实例所有的后置处理中的初始化前处理方法，为Bean实例对象在  
	        //初始化之前做一些自定义的处理操作
			result = beanProcessor.postProcessAfterInitialization(result, beanName);
			if (result == null) {
				return result;
			}
		}
		return result;
	}

	//创建Bean实例对象
	@Override
	protected Object createBean(final String beanName, final RootBeanDefinition mbd, final Object[] args)
			throws BeanCreationException {

		if (logger.isDebugEnabled()) {
			logger.debug("Creating instance of bean '" + beanName + "'");
		}
		//判断需要创建的Bean是否可以实例化，是否可以通过当前的类加载器加载
		resolveBeanClass(mbd, beanName);

		//校验和准备Bean中的方法覆盖
		try {
			mbd.prepareMethodOverrides();
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbd.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}

		try {
			//如果Bean配置了后置处理器PostProcessor，则这里返回一个proxy代理对象
			Object bean = resolveBeforeInstantiation(beanName, mbd);
			if (bean != null) {
				return bean;
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"BeanPostProcessor before instantiation of bean failed", ex);
		}

		//创建Bean的入口
		Object beanInstance = doCreateBean(beanName, mbd, args);
		if (logger.isDebugEnabled()) {
			logger.debug("Finished creating instance of bean '" + beanName + "'");
		}
		return beanInstance;
	}

	//真正创建Bean的方法 ，spring中以do开头的都是方法的具体实现
	protected Object doCreateBean(final String beanName, final RootBeanDefinition mbd, final Object[] args) {
		
		//封装被创建的Bean对象
		BeanWrapper instanceWrapper = null;
		//如果这个bean是单例的，则从缓存中获取这个BeanWrapper实例并清除
		if (mbd.isSingleton()) {
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		if (instanceWrapper == null) {
			
			/**
			 * ！！！！！！！！！！！！！
			 * 创建实例对象
			 * ！！！！！！！！！！！！！
			 */
			instanceWrapper = createBeanInstance(beanName, mbd, args);
		}
		final Object bean = (instanceWrapper != null ? instanceWrapper.getWrappedInstance() : null);
		//获取实例化对象的类型
		Class<?> beanType = (instanceWrapper != null ? instanceWrapper.getWrappedClass() : null);

		//调用PostProcessor后置处理器
		synchronized (mbd.postProcessingLock) {
			if (!mbd.postProcessed) {
				applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				mbd.postProcessed = true;
			}
		}

		//向容器中缓存单例模式的Bean对象，以防循环引用
		boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
				isSingletonCurrentlyInCreation(beanName));
		if (earlySingletonExposure) {
			if (logger.isDebugEnabled()) {
				logger.debug("Eagerly caching bean '" + beanName +
						"' to allow for resolving potential circular references");
			}
			//这里是一个匿名内部类，为了防止循环引用，尽早持有对象的引用
			addSingletonFactory(beanName, new ObjectFactory<Object>() {
				public Object getObject() throws BeansException {
					return getEarlyBeanReference(beanName, mbd, bean);
				}
			});
		}

		//Bean对象的初始化，依赖注入在此触发  
	    //这个exposedObject在初始化完成之后，将返回作为依赖注入完成后的Bean
		Object exposedObject = bean;
		try {
			/**
			 * ！！！！！！！！！！！！！！！！！！！！！！！！！！！
			 * 把生成的bean对象的依赖关系设置好，完成整个依赖注入过程
			 * ！！！！！！！！！！！！！！！！！！！！！！！！！！！
			 */
			populateBean(beanName, mbd, instanceWrapper);
			if (exposedObject != null) {
				//初始化Bean对象 
				//在对Bean实例对象生成和依赖注入完成以后，开始对Bean实例对象  
	            //进行初始化 ，为Bean实例对象应用BeanPostProcessor后置处理器
				exposedObject = initializeBean(beanName, exposedObject, mbd);
			}
		}
		catch (Throwable ex) {
			if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
				throw (BeanCreationException) ex;
			}
			else {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
			}
		}

		if (earlySingletonExposure) {
			//获取指定名称的已注册的单例模式Bean对象
			Object earlySingletonReference = getSingleton(beanName, false);
			if (earlySingletonReference != null) {
				//根据名称获取的已注册的Bean和正在实例化的Bean是同一个
				if (exposedObject == bean) {
					//当前实例化的Bean初始化完成
					exposedObject = earlySingletonReference;
				}
				//当前Bean依赖其他Bean，并且当发生循环引用时不允许新创建实例对象
				else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<String>(dependentBeans.length);
					//获取当前Bean所依赖的其他Bean 
					for (String dependentBean : dependentBeans) {
						//对依赖Bean进行类型检查
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							actualDependentBeans.add(dependentBean);
						}
					}
					if (!actualDependentBeans.isEmpty()) {
						throw new BeanCurrentlyInCreationException(beanName,
								"Bean with name '" + beanName + "' has been injected into other beans [" +
								StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
								"] in its raw version as part of a circular reference, but has eventually been " +
								"wrapped. This means that said other beans do not use the final version of the " +
								"bean. This is often the result of over-eager type matching - consider using " +
								"'getBeanNamesOfType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		//注册完成依赖注入的Bean
		try {
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}

		//为应用返回所需要的实例对象
		return exposedObject;
	}
	
	//创建Bean的实例对象
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) {
		//检查确认Bean是可实例化的
		Class<?> beanClass = resolveBeanClass(mbd, beanName);

		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}

		//使用工厂方法对Bean进行实例化
		if (mbd.getFactoryMethodName() != null)  {
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		//使用容器的自动装配方法进行实例化
		boolean resolved = false;
		boolean autowireNecessary = false;
		if (args == null) {
			synchronized (mbd.constructorArgumentLock) {
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					resolved = true;
					autowireNecessary = mbd.constructorArgumentsResolved;
				}
			}
		}
		if (resolved) {
			if (autowireNecessary) {
				//配置了自动装配属性，使用容器的自动装配实例化  
	            //容器的自动装配是根据参数类型匹配Bean的构造方法
				return autowireConstructor(beanName, mbd, null, null);
			}
			else {
				//使用默认的无参构造方法进行实例化
				return instantiateBean(beanName, mbd);
			}
		}

		//使用Bean的构造方法进行实例化
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		if (ctors != null ||
				mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_CONSTRUCTOR ||
				mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args))  {
			//使用容器的自动装配特性，调用匹配的构造方法实例化 
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		/**
		 * ！！！！！！！！！！！！！！
		 * 使用默认的无参构造方法实例化
		 * ！！！！！！！！！！！！！！
		 */
		return instantiateBean(beanName, mbd);
	}
	
	//使用默认的无参构造方法实例化Bean对象
	protected BeanWrapper instantiateBean(final String beanName, final RootBeanDefinition mbd) {
		try {
			Object beanInstance;
			final BeanFactory parent = this;
			//获取系统的安全管理接口，JDK标准的安全管理API
			if (System.getSecurityManager() != null) {
				//这里是一个匿名内置类，根据实例化策略创建实例对象
				beanInstance = AccessController.doPrivileged(new PrivilegedAction<Object>() {
					public Object run() {
						return getInstantiationStrategy().instantiate(mbd, beanName, parent);
					}
				}, getAccessControlContext());
			}
			else {
				
				/**
				 * ！！！！！！！！！！！！！！
				 * 使用初始化策略实例化Bean对象
				 * ！！！！！！！！！！！！！！
				 */
				beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, parent);
			}
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);
			initBeanWrapper(bw);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	//将Bean属性设置到生成的实例对象上
	protected void populateBean(String beanName, RootBeanDefinition mbd, BeanWrapper bw) {
		//获取BeanDefinition中设置的property，这些property来自对BeanDefinition的解析
		PropertyValues pvs = mbd.getPropertyValues();

		//如果实例对象为null，而要注入的属性值不为空，则抛出下述异常
		if (bw == null) {
			if (!pvs.isEmpty()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			}
			else {
				//实例对象为null，属性值也为空，不需要设置属性值，直接返回 
				return;
			}
		}

		//在设置属性之前调用Bean的PostProcessor后置处理器
		boolean continueWithPropertyPopulation = true;

		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof InstantiationAwareBeanPostProcessor) {
					InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
					if (!ibp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
						continueWithPropertyPopulation = false;
						break;
					}
				}
			}
		}

		if (!continueWithPropertyPopulation) {
			return;
		}

		//依赖注入开始，首先处理autowire自动装配的注入
		if (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_BY_NAME ||
				mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_BY_TYPE) {
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);

			//对autowire自动装配的处理，根据Bean名称自动装配注入
			if (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_BY_NAME) {
				autowireByName(beanName, mbd, bw, newPvs);
			}

			//根据Bean类型自动装配注入
			if (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_BY_TYPE) {
				autowireByType(beanName, mbd, bw, newPvs);
			}

			pvs = newPvs;
		}

		//检查容器是否持有用于处理单例模式Bean关闭时的后置处理器
		boolean hasInstAwareBpps = hasInstantiationAwareBeanPostProcessors();
		//Bean实例对象没有依赖，即没有继承基类
		boolean needsDepCheck = (mbd.getDependencyCheck() != RootBeanDefinition.DEPENDENCY_CHECK_NONE);

		if (hasInstAwareBpps || needsDepCheck) {
			//从实例对象中提取属性描述符
			PropertyDescriptor[] filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			if (hasInstAwareBpps) {
				for (BeanPostProcessor bp : getBeanPostProcessors()) {
					if (bp instanceof InstantiationAwareBeanPostProcessor) {
						InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
						//使用BeanPostProcessor处理器处理属性值
						pvs = ibp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
						if (pvs == null) {
							return;
						}
					}
				}
			}
			if (needsDepCheck) {
				//为要设置的属性进行依赖检查
				checkDependencies(beanName, mbd, filteredPds, pvs);
			}
		}
		/**
		 * ！！！！！！！！！！！
		 * 对属性进行依赖注入
		 * ！！！！！！！！！！！
		 */
		applyPropertyValues(beanName, mbd, bw, pvs);
	}
	
	//解析并注入依赖属性的过程
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		if (pvs == null || pvs.isEmpty()) {
			return;
		}

		//封装属性值
		MutablePropertyValues mpvs = null;
		List<PropertyValue> original;

		if (System.getSecurityManager()!= null) {
			if (bw instanceof BeanWrapperImpl) {
				//设置安全上下文，JDK安全机制
				((BeanWrapperImpl) bw).setSecurityContext(getAccessControlContext());
			}
		}

		if (pvs instanceof MutablePropertyValues) {
			mpvs = (MutablePropertyValues) pvs;
			//如果属性值已经转换
			if (mpvs.isConverted()) {
				try {
					//为实例化对象设置属性值
					bw.setPropertyValues(mpvs);
					return;
				}
				catch (BeansException ex) {
					throw new BeanCreationException(
							mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			//获取属性值对象的原始类型值
			original = mpvs.getPropertyValueList();
		}
		else {
			original = Arrays.asList(pvs.getPropertyValues());
		}

		//获取用户自定义的类型转换
		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}
		//创建一个BeanDefinition属性值解析器，将Bean定义中的属性值解析为Bean实例对象的实际值
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		//为属性的解析值创建一个副本，最后将属性值注入到实例对象中
		List<PropertyValue> deepCopy = new ArrayList<PropertyValue>(original.size());
		boolean resolveNecessary = false;
		for (PropertyValue pv : original) {
			//如果属性值已经转换，直接添加到deepCopy列表中
			if (pv.isConverted()) {
				deepCopy.add(pv);
			}
			//如果属性值需要转换
			else {
				String propertyName = pv.getName();
				//原始的属性值，即转换之前的属性值
				Object originalValue = pv.getValue();
				/**
				 * ！！！！！！！！！！！！！！！！！！！
				 * 解析属性值，对注入类型进行转换
				 * ！！！！！！！！！！！！！！！！！！！
				 */
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
				//转换之后的属性值
				Object convertedValue = resolvedValue;
				//属性值是否可以转换
				boolean convertible = bw.isWritableProperty(propertyName) &&
						!PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				if (convertible) {
					//使用用户自定义的类型转换器转换属性值
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}
				//存储转换后的属性值，避免每次属性注入时的转换工作
				if (resolvedValue == originalValue) {
					if (convertible) {
						//设置属性转换之后的值
						pv.setConvertedValue(convertedValue);
					}
					deepCopy.add(pv);
				}
				//如果：属性是可转换的，且属性原始值是字符串类型，且属性的原始类型值不是  
	            //动态生成的字符串，且属性的原始值不是集合或者数组类型
				else if (convertible && originalValue instanceof TypedStringValue &&
						!((TypedStringValue) originalValue).isDynamic() &&
						!(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
					pv.setConvertedValue(convertedValue);
					deepCopy.add(pv);
				}
				else {
					resolveNecessary = true;
					//重新封装属性的值
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}
		if (mpvs != null && !resolveNecessary) {
			//标记属性值已经转换过
			mpvs.setConverted();
		}

		//进行属性依赖注入
		try {
			/**
			 * ！！！！！！！！！！！！！！！！！！！！！
			 * 完成bean的属性值注入的入口
			 * 走AbstractPropertyAccessor中的实现方法
			 * ！！！！！！！！！！！！！！！！！！！！！
			 */
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Error setting property values", ex);
		}
	}
	
	@Override
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = mbd.getTargetType();
		if (targetType == null) {
			targetType = (mbd.getFactoryMethodName() != null ? getTypeForFactoryMethod(beanName, mbd, typesToMatch) :
					resolveBeanClass(mbd, beanName, typesToMatch));
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				mbd.setTargetType(targetType);
			}
		}
		// Apply SmartInstantiationAwareBeanPostProcessors to predict the
		// eventual type after a before-instantiation shortcut.
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					Class<?> predicted = ibp.predictBeanType(targetType, beanName);
					if (predicted != null && (typesToMatch.length != 1 || !FactoryBean.class.equals(typesToMatch[0]) ||
							FactoryBean.class.isAssignableFrom(predicted))) {
						return predicted;
					}
				}
			}
		}
		return targetType;
	}

	/**
	 * Determine the bean type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} else
	 * @see #createBean
	 */
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class[] typesToMatch) {
		Class<?> factoryClass;
		boolean isStatic = true;

		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			// Check declared factory method return type on factory class.
			factoryClass = getType(factoryBeanName);
			isStatic = false;
		}
		else {
			// Check declared factory method return type on bean class.
			factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
		}

		if (factoryClass == null) {
			return null;
		}

		// If all factory methods have the same return type, return that type.
		// Can't clearly figure out exact method due to type converting / autowiring!
		Class<?> commonType = null;
		int minNrOfArgs = mbd.getConstructorArgumentValues().getArgumentCount();
		Method[] candidates = ReflectionUtils.getUniqueDeclaredMethods(factoryClass);
		for (Method factoryMethod : candidates) {
			if (Modifier.isStatic(factoryMethod.getModifiers()) == isStatic &&
					factoryMethod.getName().equals(mbd.getFactoryMethodName()) &&
					factoryMethod.getParameterTypes().length >= minNrOfArgs) {
				// No declared type variables to inspect, so just process the standard return type.
				if (factoryMethod.getTypeParameters().length > 0) {
					try {
						// Fully resolve parameter names and argument values.
						Class<?>[] paramTypes = factoryMethod.getParameterTypes();
						String[] paramNames = null;
						ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
						if (pnd != null) {
							paramNames = pnd.getParameterNames(factoryMethod);
						}
						ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
						Set<ConstructorArgumentValues.ValueHolder> usedValueHolders =
								new HashSet<ConstructorArgumentValues.ValueHolder>(paramTypes.length);
						Object[] args = new Object[paramTypes.length];
						for (int i = 0; i < args.length; i++) {
							ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
									i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
							if (valueHolder == null) {
								valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
							}
							if (valueHolder != null) {
								args[i] = valueHolder.getValue();
								usedValueHolders.add(valueHolder);
							}
						}
						Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
								factoryMethod, args, getBeanClassLoader());
						if (returnType != null) {
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
						}
					}
					catch (Throwable ex) {
						if (logger.isDebugEnabled()) {
							logger.debug("Failed to resolve generic return type for factory method: " + ex);
						}
					}
				}
				else {
					commonType = ClassUtils.determineCommonAncestor(factoryMethod.getReturnType(), commonType);
				}
			}
		}

		if (commonType != null) {
			// Clear return type found: all factory methods return same type.
			return commonType;
		}
		else {
			// Ambiguous return types found: return null to indicate "not determinable".
			return null;
		}
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, a full creation of the FactoryBean is
	 * used as fallback (through delegation to the superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		class Holder { Class<?> value = null; }
		final Holder objectType = new Holder();
		String factoryBeanName = mbd.getFactoryBeanName();
		final String factoryMethodName = mbd.getFactoryMethodName();
		if (factoryBeanName != null && factoryMethodName != null) {
			// Try to obtain the FactoryBean's object type without instantiating it at all.
			BeanDefinition fbDef = getBeanDefinition(factoryBeanName);
			if (fbDef instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) fbDef).hasBeanClass()) {
				// CGLIB subclass methods hide generic parameters; look at the original user class.
				Class<?> fbClass = ClassUtils.getUserClass(((AbstractBeanDefinition) fbDef).getBeanClass());
				// Find the given factory method, taking into account that in the case of
				// @Bean methods, there may be parameters present.
				ReflectionUtils.doWithMethods(fbClass,
						new ReflectionUtils.MethodCallback() {
							public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
								if (method.getName().equals(factoryMethodName) &&
										FactoryBean.class.isAssignableFrom(method.getReturnType())) {
									objectType.value = GenericTypeResolver.resolveReturnTypeArgument(method, FactoryBean.class);
								}
							}
						});
				if (objectType.value != null) {
					return objectType.value;
				}
			}
		}

		FactoryBean<?> fb = (mbd.isSingleton() ?
				getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
				getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));

		if (fb != null) {
			// Try to obtain the FactoryBean's object type from this early stage of the instance.
			objectType.value = getTypeForFactoryBean(fb);
			if (objectType.value != null) {
				return objectType.value;
			}
		}

		// No type found - fall back to full creation of the FactoryBean instance.
		return super.getTypeForFactoryBean(beanName, mbd);
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param bean the raw bean instance
	 * @return the object to expose as bean reference
	 */
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		Object exposedObject = bean;
		if (bean != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					exposedObject = ibp.getEarlyBeanReference(exposedObject, beanName);
					if (exposedObject == null) {
						return exposedObject;
					}
				}
			}
		}
		return exposedObject;
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization
	 * of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			if (bw != null) {
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			if (isSingletonCurrentlyInCreation(beanName)) {
				return null;
			}
			Object instance = null;
			try {
				// Mark this bean as currently in creation, even if just partially.
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				instance = resolveBeforeInstantiation(beanName, mbd);
				if (instance == null) {
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			}
			finally {
				// Finished partial creation of this bean.
				afterSingletonCreation(beanName);
			}
			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization
	 * of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}
		Object instance = null;
		try {
			// Mark this bean as currently in creation, even if just partially.
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		}
		finally {
			// Finished partial creation of this bean.
			afterPrototypeCreation(beanName);
		}
		return getFactoryBean(beanName, instance);
	}

	/**
	 * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
	 * invoking their {@code postProcessMergedBeanDefinition} methods.
	 * @param mbd the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 * @throws BeansException if any post-processing failed
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName)
			throws BeansException {

		try {
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof MergedBeanDefinitionPostProcessor) {
					MergedBeanDefinitionPostProcessor bdp = (MergedBeanDefinitionPostProcessor) bp;
					bdp.postProcessMergedBeanDefinition(mbd, beanType, beanName);
				}
			}
		}
		catch (Exception ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Post-processing failed of bean type [" + beanType + "] failed", ex);
		}
	}

	/**
	 * Apply before-instantiation post-processors, resolving whether there is a
	 * before-instantiation shortcut for the specified bean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 */
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		Object bean = null;
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// Make sure bean class is actually resolved at this point.
			if (mbd.hasBeanClass() && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				bean = applyBeanPostProcessorsBeforeInstantiation(mbd.getBeanClass(), beanName);
				if (bean != null) {
					bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
				}
			}
			mbd.beforeInstantiationResolved = (bean != null);
		}
		return bean;
	}

	/**
	 * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
	 * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
	 * <p>Any returned object will be used as the bean instead of actually instantiating
	 * the target bean. A {@code null} return value from the post-processor will
	 * result in the target bean being instantiated.
	 * @param beanClass the class of the bean to be instantiated
	 * @param beanName the name of the bean
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 * @throws BeansException if any post-processing failed
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName)
			throws BeansException {

		for (BeanPostProcessor bp : getBeanPostProcessors()) {
			if (bp instanceof InstantiationAwareBeanPostProcessor) {
				InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
				Object result = ibp.postProcessBeforeInstantiation(beanClass, beanName);
				if (result != null) {
					return result;
				}
			}
		}
		return null;
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(Class<?> beanClass, String beanName)
			throws BeansException {

		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					Constructor<?>[] ctors = ibp.determineCandidateConstructors(beanClass, beanName);
					if (ctors != null) {
						return ctors;
					}
				}
			}
		}
		return null;
	}



	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 */
	protected BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, Object[] explicitArgs) {

		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param ctors the chosen candidate constructors
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return BeanWrapper for the new instance
	 */
	protected BeanWrapper autowireConstructor(
			String beanName, RootBeanDefinition mbd, Constructor<?>[] ctors, Object[] explicitArgs) {

		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}



	/**
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 * @param beanName the name of the bean we're wiring up.
	 * Useful for debugging messages; not used functionally.
	 * @param mbd bean definition to update through autowiring
	 * @param bw BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	//根据名称对属性进行自动依赖注入
	protected void autowireByName(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		//对Bean对象中非简单属性(不是简单继承的对象，如8中原始类型，字符串，URL等都是简单属性)进行处理  
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			//如果Spring IOC容器中包含指定名称的Bean
			if (containsBean(propertyName)) {
				//调用getBean方法向IoC容器索取指定名称的Bean实例，迭代触发属性的初始化和依赖注入 
				Object bean = getBean(propertyName);
				//为指定名称的属性赋予属性值
				pvs.add(propertyName, bean);
				//指定名称属性注册依赖Bean名称，进行属性依赖注入
				registerDependentBean(propertyName, beanName);
				if (logger.isDebugEnabled()) {
					logger.debug("Added autowiring by name from bean name '" + beanName +
							"' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName +
							"' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 * @param beanName the name of the bean to autowire by type
	 * @param mbd the merged bean definition to update through autowiring
	 * @param bw BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	//根据类型对属性进行自动依赖注入
	protected void autowireByType(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		//获取用户定义的类型转换器 
		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}

		//存放解析的要注入的属性
		Set<String> autowiredBeanNames = new LinkedHashSet<String>(4);
		//对Bean对象中非简单属性(不是简单继承的对象，如8中原始类型，字符  
        //URL等都是简单属性)进行处理 
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			try {
				//获取指定属性名称的属性描述器 
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is a unsatisfied, non-simple property.
				//不对Object类型的属性进行autowiring自动依赖注入  
				if (!Object.class.equals(pd.getPropertyType())) {
					//获取属性的setter方法
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					//检查指定类型是否可以被转换为目标对象的类型 
					boolean eager = !PriorityOrdered.class.isAssignableFrom(bw.getWrappedClass());
					//创建一个要被注入的依赖描述 
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					//根据容器的Bean定义解析依赖关系，返回所有要被注入的Bean对象  
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					if (autowiredArgument != null) {
						//为属性赋值所引用的对象  
						pvs.add(propertyName, autowiredArgument);
					}
					for (String autowiredBeanName : autowiredBeanNames) {
						//指定名称属性注册依赖Bean名称，进行属性依赖注入 
						registerDependentBean(autowiredBeanName, beanName);
						if (logger.isDebugEnabled()) {
							logger.debug("Autowiring by type from bean name '" + beanName + "' via property '" +
									propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					//释放已自动注入的属性 
					autowiredBeanNames.clear();
				}
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
	}


	/**
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 * @param mbd the merged bean definition the bean was created with
	 * @param bw the BeanWrapper the bean was created with
	 * @return an array of bean property names
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 */
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		Set<String> result = new TreeSet<String>();
		PropertyValues pvs = mbd.getPropertyValues();
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
					!BeanUtils.isSimpleProperty(pd.getPropertyType())) {
				result.add(pd.getName());
			}
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		if (filtered == null) {
			if (cache) {
				synchronized (this.filteredPropertyDescriptorsCache) {
					filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
					if (filtered == null) {
						filtered = filterPropertyDescriptorsForDependencyCheck(bw);
						this.filteredPropertyDescriptorsCache.put(bw.getWrappedClass(), filtered);
					}
				}
			}
			else {
				filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		List<PropertyDescriptor> pds =
				new LinkedList<PropertyDescriptor>(Arrays.asList(bw.getPropertyDescriptors()));
		for (Iterator<PropertyDescriptor> it = pds.iterator(); it.hasNext();) {
			PropertyDescriptor pd = it.next();
			if (isExcludedFromDependencyCheck(pd)) {
				it.remove();
			}
		}
		return pds.toArray(new PropertyDescriptor[pds.size()]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition the bean was created with
	 * @param pds the relevant property descriptors for the target bean
	 * @param pvs the property values to be applied to the bean
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, PropertyValues pvs)
			throws UnsatisfiedDependencyException {

		int dependencyCheck = mbd.getDependencyCheck();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && !pvs.contains(pd.getName())) {
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				boolean unsatisfied = (dependencyCheck == RootBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == RootBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == RootBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				if (unsatisfied) {
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}



	/**
	 * Convert the given value for the specified target property.
	 */
	private Object convertForProperty(Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {
		if (converter instanceof BeanWrapperImpl) {
			return ((BeanWrapperImpl) converter).convertForProperty(value, propertyName);
		}
		else {
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}


	/**
	 * Initialize the given bean instance, applying factory callbacks
	 * as well as init methods and bean post processors.
	 * <p>Called from {@link #createBean} for traditionally defined beans,
	 * and from {@link #initializeBean} for existing bean instances.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @return the initialized bean instance (potentially wrapped)
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	 //初始容器创建的Bean实例对象，为其添加BeanPostProcessor后置处理器
	protected Object initializeBean(final String beanName, final Object bean, RootBeanDefinition mbd) {
		//JDK的安全机制验证权限
		if (System.getSecurityManager() != null) {
			//实现PrivilegedAction接口的匿名内部类
			AccessController.doPrivileged(new PrivilegedAction<Object>() {
				public Object run() {
					//为Bean实例对象包装相关属性，如名称，类加载器，所属容器等信息
					invokeAwareMethods(beanName, bean);
					return null;
				}
			}, getAccessControlContext());
		}
		else {
			invokeAwareMethods(beanName, bean);
		}

		Object wrappedBean = bean;
		//对BeanPostProcessor后置处理器的postProcessBeforeInitialization  
	    //回调方法的调用，为Bean实例初始化前做一些处理  
		if (mbd == null || !mbd.isSynthetic()) {
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}

		//调用Bean实例对象初始化的方法，这个初始化方法是在Spring Bean定义配置  
	    //文件中通过init-method属性指定的
		try {
			invokeInitMethods(beanName, wrappedBean, mbd);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null),
					beanName, "Invocation of init method failed", ex);
		}

		//对BeanPostProcessor后置处理器的postProcessAfterInitialization  
	    //回调方法的调用，为Bean实例初始化之后做一些处理
		if (mbd == null || !mbd.isSynthetic()) {
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}
		return wrappedBean;
	}

	private void invokeAwareMethods(final String beanName, final Object bean) {
		if (bean instanceof Aware) {
			if (bean instanceof BeanNameAware) {
				((BeanNameAware) bean).setBeanName(beanName);
			}
			if (bean instanceof BeanClassLoaderAware) {
				((BeanClassLoaderAware) bean).setBeanClassLoader(getBeanClassLoader());
			}
			if (bean instanceof BeanFactoryAware) {
				((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * Give a bean a chance to react now all its properties are set,
	 * and a chance to know about its owning bean factory (this object).
	 * This means checking whether the bean implements InitializingBean or defines
	 * a custom init method, and invoking the necessary callback(s) if it does.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the merged bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @throws Throwable if thrown by init methods or by the invocation process
	 * @see #invokeCustomInitMethod
	 */
	protected void invokeInitMethods(String beanName, final Object bean, RootBeanDefinition mbd)
			throws Throwable {

		boolean isInitializingBean = (bean instanceof InitializingBean);
		if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
			if (logger.isDebugEnabled()) {
				logger.debug("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}
			if (System.getSecurityManager() != null) {
				try {
					AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
						public Object run() throws Exception {
							((InitializingBean) bean).afterPropertiesSet();
							return null;
						}
					}, getAccessControlContext());
				}
				catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			}
			else {
				((InitializingBean) bean).afterPropertiesSet();
			}
		}

		if (mbd != null) {
			String initMethodName = mbd.getInitMethodName();
			if (initMethodName != null && !(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
					!mbd.isExternallyManagedInitMethod(initMethodName)) {
				invokeCustomInitMethod(beanName, bean, mbd);
			}
		}
	}

	/**
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, final Object bean, RootBeanDefinition mbd) throws Throwable {
		String initMethodName = mbd.getInitMethodName();
		final Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));
		if (initMethod == null) {
			if (mbd.isEnforceInitMethod()) {
				throw new BeanDefinitionValidationException("Couldn't find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			}
			else {
				if (logger.isDebugEnabled()) {
					logger.debug("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				return;
			}
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}

		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
				public Object run() throws Exception {
					ReflectionUtils.makeAccessible(initMethod);
					return null;
				}
			});
			try {
				AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
					public Object run() throws Exception {
						initMethod.invoke(bean);
						return null;
					}
				}, getAccessControlContext());
			}
			catch (PrivilegedActionException pae) {
				InvocationTargetException ex = (InvocationTargetException) pae.getException();
				throw ex.getTargetException();
			}
		}
		else {
			try {
				ReflectionUtils.makeAccessible(initMethod);
				initMethod.invoke(bean);
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}


	/**
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		super.removeSingleton(beanName);
		this.factoryBeanInstanceCache.remove(beanName);
	}


	/**
	 * Special DependencyDescriptor variant for autowire="byType".
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		public String getDependencyName() {
			return null;
		}
	}

}
