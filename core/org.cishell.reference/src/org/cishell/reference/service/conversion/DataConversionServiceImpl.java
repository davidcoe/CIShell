/* ****************************************************************************
 * CIShell: Cyberinfrastructure Shell, An Algorithm Integration Framework.
 * 
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Apache License v2.0 which accompanies
 * this distribution, and is available at:
 * http://www.apache.org/licenses/LICENSE-2.0.html
 * 
 * Created on Jun 15, 2006 at Indiana University.
 * 
 * Contributors:
 *     Indiana University -
 * ***************************************************************************/
package org.cishell.reference.service.conversion;

import java.io.File;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.cishell.framework.CIShellContext;
import org.cishell.framework.algorithm.AlgorithmFactory;
import org.cishell.framework.algorithm.AlgorithmProperty;
import org.cishell.framework.data.Data;
import org.cishell.service.conversion.ConversionException;
import org.cishell.service.conversion.Converter;
import org.cishell.service.conversion.DataConversionService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogService;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath;
import edu.uci.ics.jung.graph.Edge;
import edu.uci.ics.jung.graph.Graph;
import edu.uci.ics.jung.graph.Vertex;
import edu.uci.ics.jung.graph.impl.DirectedSparseEdge;
import edu.uci.ics.jung.graph.impl.DirectedSparseGraph;
import edu.uci.ics.jung.graph.impl.SparseVertex;
import edu.uci.ics.jung.io.GraphMLFile;
import edu.uci.ics.jung.utils.UserDataContainer;

/**
 * Builds converter chains from one data type to another
 * 
 * @author Bruce Herr
 * @author Ben Markines
 *
 */
public class DataConversionServiceImpl implements DataConversionService, AlgorithmProperty, ServiceListener {
	public final static String SERVICE_LIST = "SERVICE_LIST";

	private BundleContext bContext;
	private CIShellContext ciContext;
	private Map<String,Vertex> dataTypeToVertex;
	// TODO: upgrade Jung, use new Graph<V,E>
	private Graph graph;

	/**
	 * Set up to listen for service requests and initial set up of the graph
	 * 
	 * @param bContext Current bundle context
	 * @param ciContext Current CIShell context
	 */
	public DataConversionServiceImpl(BundleContext bContext, CIShellContext ciContext) {
		this.bContext = bContext;
		this.ciContext = ciContext;

		this.graph = new DirectedSparseGraph();
		this.dataTypeToVertex = new HashMap<String,Vertex>();

		String filter = "(&("+ALGORITHM_TYPE+"="+TYPE_CONVERTER+")" +
				"("+IN_DATA+"=*) " +
				"("+OUT_DATA+"=*)" +
				"(!("+REMOTE+"=*))" +
				"(!("+IN_DATA+"=file-ext:*))" +
				"(!("+OUT_DATA+"=file-ext:*)))";

		try {
			this.bContext.addServiceListener(this, filter);
		} catch (InvalidSyntaxException e) {
			e.printStackTrace();
		}
		assembleGraph();
	}

	/**
	 * Assemble the directed graph of converters.  Currently unweighted.
	 */
	private void assembleGraph() {
		try {
			String filter = "(&("+ALGORITHM_TYPE+"="+TYPE_CONVERTER+")" +
					"("+IN_DATA+"=*) " +
					"("+OUT_DATA+"=*)" +
					"(!("+REMOTE+"=*))" +
					"(!("+IN_DATA+"=file-ext:*))" +
					"(!("+OUT_DATA+"=file-ext:*)))";

			Collection<ServiceReference<AlgorithmFactory>> refs = getAFServiceReferences(filter);

			if (refs != null) {
				for (ServiceReference<AlgorithmFactory> ref : refs) {
					String inData = (String) ref
							.getProperty(AlgorithmProperty.IN_DATA);
					String outData = (String) ref
							.getProperty(AlgorithmProperty.OUT_DATA);

					addServiceReference(inData, outData, ref);
				}
			}
		} catch (InvalidSyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Get the converter chains for incoming and outgoing format
	 * 
	 * @param inFormat The format to convert from
	 * @param outFormat The format to convert to
	 */
	public Converter[] findConverters(String inFormat, String outFormat) {
		//      saveGraph();
		if (inFormat != null && inFormat.length() > 0
				&& outFormat != null && outFormat.length() > 0) {

			ConverterImpl[] converters = null;

			if (outFormat.startsWith("file-ext:")) {
				converters = getConvertersByWildcard(inFormat, "file:*");
				converters = connectValidator(converters, outFormat);
			} else {
				converters = getConvertersByWildcard(inFormat, outFormat);
			}

			return converters;
		}
		return new Converter[0];
	}

	/**
	 * If the final format is of type file-ext, then append the final converter
	 * to the converter list
	 * 
	 * @param converters Current converter chains
	 * @param outFormat Final data type
	 * @return The edited converter chains
	 */
	private ConverterImpl[] connectValidator(ConverterImpl[] converters,
			String outFormat) {
		Collection<ConverterImpl> newConverters = Sets.newHashSet();

		Set<String> formats = Sets.newHashSet();
		for (int i = 0; i < converters.length; i++) {
			String format = (String) converters[i].getProperties().get(OUT_DATA);

			if (!formats.contains(format)) {
				String filter = "(&("+ALGORITHM_TYPE+"="+TYPE_VALIDATOR+")" +
						"(!("+REMOTE+"=*))" +
						"("+IN_DATA+"="+format+")" +
						"("+OUT_DATA+"="+outFormat+"))";

				try {
					Collection<ServiceReference<AlgorithmFactory>> refs =
							getAFServiceReferences(filter);

					if (refs != null && refs.size() > 0) {
						for (ServiceReference<AlgorithmFactory> ref : refs) {
							List<ServiceReference<AlgorithmFactory>> chain = 
									Lists.newArrayList(converters[i].getConverterList());
							chain.add(ref);

							newConverters.add(ConverterImpl.createConverter(bContext, ciContext,
									chain));
						}

						formats.add(format);
					}
				} catch (InvalidSyntaxException e) {
					e.printStackTrace();
				}
			}
		}

		return newConverters.toArray(new ConverterImpl[0]);
	}

	private Collection<ServiceReference<AlgorithmFactory>> getAFServiceReferences(String filter) throws InvalidSyntaxException {
		List<ServiceReference<AlgorithmFactory>> refList = Lists.newArrayList();

		// We're guaranteed to get a ServiceReference<AlgorithmFactory>[] back, because
		// we pass the AlgorithmFactory class name into the method.
		// When we switch to a new version of OSGi, this ugliness can be replaced with the
		// a call to bContext.getServiceReferences(Class, String).
		@SuppressWarnings("unchecked")
		ServiceReference<AlgorithmFactory>[] refArray = 
				(ServiceReference<AlgorithmFactory>[]) bContext.getServiceReferences(
						AlgorithmFactory.class.getName(),
						filter);

		if (refArray != null && refArray.length > 0) {
			refList.addAll((Collection<? extends ServiceReference<AlgorithmFactory>>) Arrays.asList(refArray));
		}

		return refList;
	}

	/**
	 * Finds data types that match a given wildcard, using a particular algorithm property.
	 * 
	 * @param formatString
	 * @param algorithmProperty - one of AlgorithmProperties.IN_DATA or AlgorithmProperties.OUT_DATA
	 * @return
	 */
	private Set<String> resolveDataWildcard(String formatString, String algorithmProperty) {
		Set<String> expansions = new HashSet<String>();
		if (! formatString.contains("*")) {
			expansions.add(formatString);
			return expansions;
		}
		
		String algorithmFilter;
		if (algorithmProperty.equals(AlgorithmProperty.IN_DATA)) {
			algorithmFilter = createConverterFilterForInFormat(formatString);
		} else if (algorithmProperty.equals(AlgorithmProperty.OUT_DATA)) {
			algorithmFilter = createConverterFilterForOutFormat(formatString);
		} else {
			throw new IllegalArgumentException(String.format(
					"Got algorithm property %s, expected one of AlgorithmProperty.{IN,OUT}_DATA",
					algorithmProperty));
		}
		
		
		try {
			Collection<ServiceReference<AlgorithmFactory>> matches
				= getAFServiceReferences(algorithmFilter);
			for (ServiceReference<AlgorithmFactory> match : matches) {
				expansions.add((String) match.getProperty(algorithmProperty));
			}
		} catch (InvalidSyntaxException e) {
			getLogger().log(LogService.LOG_WARNING, String.format("Error while looking for %s: %s", formatString, e.getMessage()));
		}
		
		
		return expansions;
	}
	
	private LogService getLogger() {
		return (LogService) ciContext.getService(LogService.class.getName());
	}

	/**
	 * Build the converter chains.  The data formats may include wildcards.
	 * @param inFormat The original data type
	 * @param outFormat The target data type
	 * @return Converter chains
	 * @see org.cishell.service.conversion.DataConversionService#findConverters(java.lang.String, java.lang.String)
	 */
	private ConverterImpl[] getConvertersByWildcard(String inFormat, String outFormat) {
		Set<String>  matchingInFileTypes = resolveDataWildcard(inFormat,  AlgorithmProperty.IN_DATA),
					matchingOutFileTypes = resolveDataWildcard(outFormat, AlgorithmProperty.OUT_DATA);
		Set<ConverterImpl> possibleConverters = new HashSet<ConverterImpl>();

		//Check to see if inFormat matches the outFormat (for example:
		//in=file:text/graphml out=file:* If so, then add a null converter
		//to the converterList.
		if (outFormat.contains("*")) {
			String outFormatRegex = outFormat.replaceAll("[*]", ".*");
			if (Pattern.matches(outFormatRegex, inFormat)) {
				possibleConverters.add(ConverterImpl.createNoOpConverter(bContext, ciContext, inFormat));
			}
		}
		
		for (String srcDataType : matchingInFileTypes) {
			for (String destDataType : matchingOutFileTypes) {
				ConverterImpl converter =
						getConverter(srcDataType, destDataType);
				if (converter != null) {
					possibleConverters.add(converter);
				}
			}
		}
		return possibleConverters.toArray(new ConverterImpl[0]);
	}

	private String createConverterFilterForOutFormat(String outFormat) {
		String outFilter = "(&(" + ALGORITHM_TYPE + "=" + TYPE_CONVERTER + ")"
				+ "("+IN_DATA+"=*) " + "("+OUT_DATA+"="+outFormat+")"
				+ "(!(" + REMOTE + "=*)))";
		return outFilter;
	}

	private String createConverterFilterForInFormat(String inFormat) {
		String inFilter = "(&(" + ALGORITHM_TYPE + "=" + TYPE_CONVERTER + ")"
				+ "("+IN_DATA+"="+inFormat+") " + "("+OUT_DATA+"=*)" +
				"(!("+IN_DATA+"=file-ext:*))" + "(!(" + REMOTE + "=*)))";
		return inFilter;
	}

	/**
	 * Get the shortest converter path.  This returns a single converter path
	 * @param inType The source data type
	 * @param outType The target data type
	 * @return Single converter path, or null if no path can be found
	 */
	private ConverterImpl getConverter(String inType, String outType) {
		Vertex sourceVertex = dataTypeToVertex.get(inType);
		Vertex targetVertex = dataTypeToVertex.get(outType);

		if (sourceVertex != null && targetVertex != null) {
			DijkstraShortestPath shortestPathAlg =
					new DijkstraShortestPath(graph);
			
			// Documentation of DijkstraShortestPath indicates that this returns a list of Edge.
			@SuppressWarnings("unchecked")
			List<Edge> edgeList = shortestPathAlg.getPath(sourceVertex, targetVertex);

			if (edgeList.size() > 0) {
				List<ServiceReference<AlgorithmFactory>> serviceReferences = Lists.newArrayList();
				for (Edge edge : edgeList) {
					AbstractList converterList =
							(AbstractList) edge.getUserDatum(SERVICE_LIST);
					serviceReferences.add((ServiceReference) converterList.get(0));
				}

				return ConverterImpl.createConverter(bContext, ciContext, serviceReferences);
			}
		}

		return null;
	}

	/**
	 * Builds the converter chains using data as the source
	 * @param data The source data to convert from
	 * @param outFormat the final data type
	 * @return Converter chains
	 * @see org.cishell.service.conversion.DataConversionService#findConverters(org.cishell.framework.data.Data, java.lang.String)
	 */
	public Converter[] findConverters(Data data, String outFormat) {
		if (data == null) {
			if (NULL_DATA.equalsIgnoreCase(""+outFormat)) {
				return new Converter[]{ConverterImpl.createNoOpConverter(bContext, ciContext, outFormat)};
			} else {
				return new Converter[0];
			}
		}

		String format = data.getFormat();

		Set set = new HashSet();
		Converter[] converters = new Converter[0];
		if (format != null) {
			converters = findConverters(format, outFormat);
			set.addAll(new HashSet(Arrays.asList(converters)));
		}
		if (!(data.getData() instanceof File) && data.getData() != null) {
			Class dataClass = data.getData().getClass();
			for (Iterator it = getClassesFor(dataClass).iterator();
					it.hasNext();) {
				Class c = (Class) it.next();
				converters = findConverters(c.getName(), outFormat);
				set.addAll(new HashSet(Arrays.asList(converters)));
			}
		}

		return (Converter[]) set.toArray(new Converter[0]);
	}

	/**
	 * Get all the classes implemented and extended
	 * @param clazz The class to query
	 * @return Interfaces and base classes
	 */
	protected Collection getClassesFor(Class clazz) {
		Set classes = new HashSet();

		Class[] c = clazz.getInterfaces();
		for (int i=0; i < c.length; i++) {
			classes.addAll(getClassesFor(c[i]));
		}

		Class superC = clazz.getSuperclass();

		if (superC != Object.class) {
			if (superC != null)
				classes.addAll(getClassesFor(superC));
		} else {
			classes.add(superC);
		}

		classes.add(clazz);

		return classes;
	}

	/**
	 * Convert the Data to a format
	 * @param inDM Data type to convert
	 * @param outFormat The data type to convert
	 * @return The final data type
	 * @see org.cishell.service.conversion.DataConversionService#convert(org.cishell.framework.data.Data, java.lang.String)
	 */
	public Data convert(Data inDM, String outFormat) throws ConversionException {
		String inFormat = inDM.getFormat();

		if (inFormat != null && inFormat.equals(outFormat)) {
			return inDM;
		}

		Converter[] converters = findConverters(inDM, outFormat);
		if (converters.length > 0) {
			inDM = converters[0].convert(inDM);
		}

		return inDM;
	}

	/**
	 * Change service reference in the graph
	 * @param event The service that changed
	 */
	public void serviceChanged(ServiceEvent event) {
		ServiceReference<?> inServiceRef = event.getServiceReference();

		String inDataType =
				(String) inServiceRef.getProperty(AlgorithmProperty.IN_DATA);
		String outDataType =
				(String) inServiceRef.getProperty(AlgorithmProperty.OUT_DATA);

		if (event.getType() ==  ServiceEvent.MODIFIED) {
			removeServiceReference(inDataType, outDataType, inServiceRef);
			addServiceReference(inDataType, outDataType, inServiceRef);
		}
		else if(event.getType() == ServiceEvent.REGISTERED) {
			addServiceReference(inDataType, outDataType, inServiceRef);
		}
		else if(event.getType() == ServiceEvent.UNREGISTERING) {
			removeServiceReference(inDataType, outDataType, inServiceRef);
		}
	}

	/**
	 * Remove a service reference in the graph
	 * @param sourceDataType The source data type of the serviceReference to remove
	 * @param targetDataType The target data type of the serviceReference to remove
	 * @param serviceReference The serviceReference to remove
	 */
	private void removeServiceReference(String sourceDataType,
			String targetDataType,
			ServiceReference<?> serviceReference) {
		if (sourceDataType != null && targetDataType != null) {
			Vertex sourceVertex = (Vertex) dataTypeToVertex.get(sourceDataType);
			Vertex targetVertex = (Vertex) dataTypeToVertex.get(targetDataType);
			String pid =
					(String) serviceReference.getProperty(Constants.SERVICE_PID);

			if (sourceVertex != null && targetVertex != null) {
				Edge edge = sourceVertex.findEdge(targetVertex);
				if (edge != null) {
					AbstractList serviceList =
							(AbstractList) edge.getUserDatum(SERVICE_LIST);
					for (Iterator refs = serviceList.iterator(); refs.hasNext();) {
						ServiceReference currentServiceReference =
								(ServiceReference) refs.next();
						String currentPid =
								(String) currentServiceReference
								.getProperty(Constants.SERVICE_PID);

						if (pid.equals(currentPid)) {
							refs.remove();
						}
					}
					if (serviceList.isEmpty()) {
						graph.removeEdge(edge);
					}
				}
			}
		}
	}

	/**
	 * Add service reference to the graph
	 * @param sourceDataType The source data type
	 * @param targetDataType The target data type
	 * @param serviceReference The service reference to add
	 */
	private void addServiceReference(String sourceDataType,
			String targetDataType,
			ServiceReference<?> serviceReference) {
		if (sourceDataType != null && sourceDataType.length() > 0
				&& targetDataType != null && targetDataType.length() > 0) {
			Vertex sourceVertex = getVertex(sourceDataType);
			Vertex targetVertex = getVertex(targetDataType);

			removeServiceReference(
					sourceDataType,	targetDataType, serviceReference);

			Edge directedEdge = sourceVertex.findEdge(targetVertex);
			if (directedEdge == null) {
				directedEdge =
						new DirectedSparseEdge(sourceVertex, targetVertex);
				graph.addEdge(directedEdge);
			}

			AbstractList serviceList =
					(AbstractList) directedEdge.getUserDatum(SERVICE_LIST);
			if (serviceList == null) {
				serviceList = new ArrayList();
				serviceList.add(serviceReference);
			}
			directedEdge.setUserDatum(SERVICE_LIST, serviceList,
					new UserDataContainer.CopyAction.Shared());
		}
	}

	/**
	 * Get the vertex in the graph given a data type.  Creates a new vertex
	 * if one does not exist
	 * @param dataType Datatype representing the node
	 * @return The vertex
	 */
	private Vertex getVertex(String dataType) {
		Vertex vertex = (SparseVertex) dataTypeToVertex.get(dataType);
		if (vertex== null) {
			vertex = new SparseVertex();
			vertex.addUserDatum("label",
					dataType,
					new UserDataContainer.CopyAction.Shared());
			graph.addVertex(vertex);
			dataTypeToVertex.put(dataType, vertex);
		}
		return vertex;
	}

	/**
	 * Save the current converter graph to the user's home directory

	 */
	private void saveGraph() {
		GraphMLFile writer = new GraphMLFile();
		Graph g = (Graph) graph.copy();
		for (Iterator edges = g.getEdges().iterator(); edges.hasNext();) {
			Edge e = (Edge) edges.next();
			e.removeUserDatum(SERVICE_LIST);
		}

		writer.save(g, System.getProperty("user.home")
				+ File.separator
				+ "convertGraph.xml");
	}
}
