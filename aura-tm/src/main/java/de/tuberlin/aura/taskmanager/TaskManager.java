package de.tuberlin.aura.taskmanager;

import io.netty.channel.Channel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import de.tuberlin.aura.core.common.eventsystem.Event;
import de.tuberlin.aura.core.common.eventsystem.EventDispatcher;
import de.tuberlin.aura.core.common.eventsystem.IEventDispatcher;
import de.tuberlin.aura.core.common.eventsystem.IEventHandler;
import de.tuberlin.aura.core.common.utils.Pair;
import de.tuberlin.aura.core.descriptors.Descriptors.MachineDescriptor;
import de.tuberlin.aura.core.descriptors.Descriptors.TaskBindingDescriptor;
import de.tuberlin.aura.core.descriptors.Descriptors.TaskDescriptor;
import de.tuberlin.aura.core.iosystem.IOEvents;
import de.tuberlin.aura.core.iosystem.IOManager;
import de.tuberlin.aura.core.iosystem.IOMessages.DataMessage;
import de.tuberlin.aura.core.iosystem.RPCManager;
import de.tuberlin.aura.core.protocols.WM2TMProtocol;
import de.tuberlin.aura.core.task.common.TaskContext;
import de.tuberlin.aura.core.task.common.TaskInvokeable;
import de.tuberlin.aura.core.task.common.TaskStateMachine;
import de.tuberlin.aura.core.task.common.TaskStateMachine.TaskState;
import de.tuberlin.aura.core.task.common.TaskStateMachine.TaskTransition;
import de.tuberlin.aura.core.task.usercode.UserCode;
import de.tuberlin.aura.core.task.usercode.UserCodeImplanter;
import de.tuberlin.aura.taskmanager.Handler.AbstractTaskEventHandler;
import de.tuberlin.aura.taskmanager.TaskEvents.TaskStateTransitionEvent;

public final class TaskManager implements WM2TMProtocol {

	//---------------------------------------------------
    // Inner Classes.
    //---------------------------------------------------
	
	/**
	 * 
	 */
	private final class IOHandler implements IEventHandler {

		@Override
		public void handleEvent( final Event e) {
			if( e instanceof IOEvents.IODataChannelEvent ) {
				
				final IOEvents.IODataChannelEvent event = (IOEvents.IODataChannelEvent)e;				
				Pair<TaskContext,IEventDispatcher> contextAndHandler = null; 
				// Call the correct handler!
				if( IOEvents.IODataChannelEvent.IO_EVENT_OUTPUT_CHANNEL_CONNECTED.equals( event.type ) )
					contextAndHandler = taskContextMap.get( event.srcTaskID );
				if( IOEvents.IODataChannelEvent.IO_EVENT_INPUT_CHANNEL_CONNECTED.equals( event.type ) )
					contextAndHandler = taskContextMap.get( event.dstTaskID );				
				// check state.
				if( contextAndHandler == null )
					throw new IllegalStateException( "contextAndHandler for task " 
								+ event.dstTaskID + " is null" );
				final IEventDispatcher dispatcher = contextAndHandler.getSecond(); 				
				dispatcher.dispatchEvent( event );
				
			} else if( e instanceof IOEvents.IODataEvent ) {
				
				final IOEvents.IODataEvent event = (IOEvents.IODataEvent)e;
				final Pair<TaskContext,IEventDispatcher> contextAndHandler = 
						taskContextMap.get( event.message.dstTaskID );
				contextAndHandler.getSecond().dispatchEvent( event );
			
			} else {
				throw new IllegalStateException( "unknown IO event" );
			}
		}
	}

	/**
	 * 
	 */
	public final class TaskEventHandler extends AbstractTaskEventHandler {
		
		private Map<UUID,Integer> taskIDToChannelIndex;
		
		@Override
		protected void initHandler() {
			taskIDToChannelIndex = new HashMap<UUID,Integer>();
			int channelIndex = 0;
			for( TaskDescriptor inputTask : context.taskBinding.inputs ) {			
				taskIDToChannelIndex.put( inputTask.uid, channelIndex++ );
			}
		}
		
		@Override
		protected void handleTaskInputDataChannelConnect( UUID srcTaskID, UUID dstTaskID, Channel channel ) {
			int channelIndex = 0;		
			boolean allInputChannelsConnected = true, connectingToCorrectTask = false;		
			for( TaskDescriptor inputTask : context.taskBinding.inputs ) {						
				// Set the channel on right position.
				if( inputTask.uid.equals( srcTaskID ) ) {
					context.inputChannel[channelIndex] = channel;
					LOG.info( "input connection from " + inputTask.name + " [" + inputTask.uid + "] to task " 
							+ context.task.name + " [" + context.task.uid + "] is established" );			
					connectingToCorrectTask |= true;
				}				
				// all data outputs are connected...
				allInputChannelsConnected &= ( context.inputChannel[channelIndex++] != null );
			}			
			// Check if the incoming channel is connecting to the correct task.
			if( !connectingToCorrectTask )
				throw new IllegalStateException( "wrong data channel tries to connect" );			
			
			if( allInputChannelsConnected ) {				
				context.dispatcher.dispatchEvent( new TaskStateTransitionEvent( TaskTransition.TASK_TRANSITION_INPUTS_CONNECTED ) ); 				
			}		
		} 
		
		@Override
		protected void handleTaskOutputDataChannelConnect( UUID srcTaskID, UUID dstTaskID, Channel channel ) {			
			int channelIndex = 0;		
			boolean allOutputChannelsConnected = true;		
			for( TaskDescriptor outputTask : context.taskBinding.outputs ) {						
				// Set the channel on right position.
				if( outputTask.uid.equals( dstTaskID ) ) {
					context.outputChannel[channelIndex] = channel;
					LOG.info( "output connection from " + context.task.name + " [" + context.task.uid + "] to task " 
							+ outputTask.name + " [" + outputTask.uid + "] is established" );	
				}
				// all data outputs are connected...
				allOutputChannelsConnected &= ( context.outputChannel[channelIndex++] != null );
			}		

			if( allOutputChannelsConnected ) {
				context.dispatcher.dispatchEvent( new TaskStateTransitionEvent( TaskTransition.TASK_TRANSITION_OUTPUTS_CONNECTED ) ); 								
			}
		}
		
		@Override
		protected void handleTaskStateTransition( TaskState currentState, TaskTransition transition ) {			
			synchronized( context.state ) { // serialize task state transitions!
				final TaskState oldState = context.state;
				final Map<TaskTransition,TaskState> transitionsSpace = 
						TaskStateMachine.TASK_STATE_TRANSITION_MATRIX.get( context.state );
				final TaskState nextState = transitionsSpace.get( transition );
				context.state = nextState;
				// Trigger state dependent actions. Realization of a classic Moore automata.
				switch( context.state ) {				
					case TASK_STATE_NOT_CONNECTED: {} break;
					case TASK_STATE_INPUTS_CONNECTED: {} break;
					case TASK_STATE_OUTPUTS_CONNECTED: {} break;
					case TASK_STATE_READY: { scheduleTask( context ); } break;					
					case TASK_STATE_RUNNING: {} break;					
					case TASK_STATE_FINISHED: {} break;		
					case TASK_STATE_FAILURE: {} break;					
					case TASK_STATE_UNDEFINED: {
						throw new IllegalStateException( "task " + context.task.name + " [" + context.task.uid + "] from state " 
								+ oldState + " to " + context.state + " is not defined" );						
					}
				}
				LOG.info( "change state of task " + context.task.name + " [" + context.task.uid + "] from " 
						+ oldState + " to " + context.state );
			}
		}
		
		@Override
		protected void handleInputData( DataMessage message ) {			
			//int channelIndex = 0;
			//for( TaskDescriptor inputTask : context.taskBinding.inputs ) {			
			//	if( inputTask.uid.equals( message.srcTaskID ) )
			//		break;
			//	++channelIndex;			
			//}
			
			// TODO: we should provide in TaskContext mappings in both direction 
			// between channelIndex and taskID for task inputs and outputs!  
			
			context.inputQueues.get( taskIDToChannelIndex.get( message.srcTaskID ) ).add( message );
		}
		
		@Override
		protected void handleTaskException() {
		}
	}
	
	//---------------------------------------------------
    // Constructors.
    //---------------------------------------------------
	
	public TaskManager( final MachineDescriptor machine ) {
		// sanity check.
		if( machine == null )
			throw new IllegalArgumentException( "machine must not be null" );
			
		this.taskContextMap = new ConcurrentHashMap<UUID, Pair<TaskContext,IEventDispatcher>>();
		
		this.ioManager = new IOManager( machine );
	
		this.rpcManager = new RPCManager( ioManager );
		
		this.ioHandler = new IOHandler();
		
		this.codeImplanter = new UserCodeImplanter( this.getClass().getClassLoader() );
		
		final int N = 4;
		this.executionUnit = new TaskExecutionUnit[N];	
		for( int i = 0; i < N; ++i ) {
			this.executionUnit[i] = new TaskExecutionUnit( i );
			this.executionUnit[i].start();
		}
		
		registerIOEvents( ioHandler );
		
		rpcManager.registerRPCProtocolImpl( this, WM2TMProtocol.class );
	}

	//---------------------------------------------------
    // Fields.
    //--------------------------------------------------- 
	
	private static final Logger LOG = Logger.getLogger( TaskManager.class ); 
			
	private final Map<UUID, Pair<TaskContext,IEventDispatcher>> taskContextMap;
		
	private final IOManager ioManager; 
	
	private final IOHandler ioHandler;
	
	private final RPCManager rpcManager;
	
	private final TaskExecutionUnit[] executionUnit;
	
	private final UserCodeImplanter codeImplanter;
	
	//---------------------------------------------------
    // Public.
    //---------------------------------------------------	
	
	@Override
	public void installTask( TaskDescriptor taskDescriptor, TaskBindingDescriptor taskBindingDescriptor,
			UserCode userCode ) {
		// sanity check.
		if( taskDescriptor == null )
			throw new IllegalArgumentException( "taskDescriptor must not be null" );	
		if( taskBindingDescriptor == null )
			throw new IllegalArgumentException( "taskBindingDescriptor must not be null" );
		if( userCode == null )
			throw new IllegalArgumentException( "taskUserCode must not be null" );

		// TODO: unpack and load all classes contained in taskUserCode. 
		@SuppressWarnings("unchecked")
		final Class<? extends TaskInvokeable> userCodeClass = 
			(Class<? extends TaskInvokeable>) codeImplanter.implantUserCodeClass( userCode ); 
		
		installTask( taskDescriptor, taskBindingDescriptor, userCodeClass );
	}
	
	// TODO: Make that later private!
	public void installTask( final TaskDescriptor taskDescriptor, final TaskBindingDescriptor taskBindingDescriptor, 
			final Class<? extends TaskInvokeable> executableClass ) {
		
		final TaskEventHandler handler = new TaskEventHandler();
		final IEventDispatcher dispatcher = registerTaskEvents( new EventDispatcher(), handler );
		final TaskContext taskContext = new TaskContext( taskDescriptor, taskBindingDescriptor, handler, dispatcher, executableClass );		
		handler.setContext( taskContext );		
		taskContextMap.put( taskDescriptor.uid, new Pair<TaskContext,IEventDispatcher>( taskContext, dispatcher ) );	
				
		if( taskBindingDescriptor.inputs.size() == 0 ) {
			taskContext.dispatcher.dispatchEvent( new TaskStateTransitionEvent( TaskTransition.TASK_TRANSITION_INPUTS_CONNECTED ) );
		}
		
		if( taskBindingDescriptor.outputs.size() == 0 ) {
			taskContext.dispatcher.dispatchEvent( new TaskStateTransitionEvent( TaskTransition.TASK_TRANSITION_OUTPUTS_CONNECTED ) );			
		}		
		
		// TODO: To allow cycles in the execution graph we have to split up 
		// installation and wiring of tasks in the deployment phase!
		wireOutputDataChannels( taskDescriptor, taskBindingDescriptor );		
	}
		
	public RPCManager getRPCManager() {
		return rpcManager;
	}
	
	//---------------------------------------------------
    // Private.
    //---------------------------------------------------
	
	private synchronized void wireOutputDataChannels( final TaskDescriptor taskDescriptor, 
			final TaskBindingDescriptor taskBindingDescriptor ) {

		// Connect outputs, if we have some...
		if( taskBindingDescriptor.outputs.size() > 0 ) {			
			for( final TaskDescriptor outputTask : taskBindingDescriptor.outputs ) {				
				ioManager.connectDataChannel( taskDescriptor.uid, outputTask.uid, outputTask.machine );
			}
		}
	}
	
	private void registerIOEvents( final IEventHandler handler ) {
		this.ioManager.addEventListener( IOEvents.IODataChannelEvent.IO_EVENT_INPUT_CHANNEL_CONNECTED, handler );		
		this.ioManager.addEventListener( IOEvents.IODataChannelEvent.IO_EVENT_OUTPUT_CHANNEL_CONNECTED, handler );
		this.ioManager.addEventListener( IOEvents.IODataEvent.IO_EVENT_RECEIVED_DATA, handler );
	}
	
	private IEventDispatcher registerTaskEvents( final IEventDispatcher dispatcher, final IEventHandler handler ) {		
		dispatcher.addEventListener( IOEvents.IODataChannelEvent.IO_EVENT_OUTPUT_CHANNEL_CONNECTED, handler );				
		dispatcher.addEventListener( IOEvents.IODataChannelEvent.IO_EVENT_INPUT_CHANNEL_CONNECTED, handler );
		dispatcher.addEventListener( IOEvents.IODataEvent.IO_EVENT_RECEIVED_DATA, handler );
		dispatcher.addEventListener( TaskStateTransitionEvent.TASK_STATE_TRANSITION_EVENT, handler );
		return dispatcher;
	}
	
	private void scheduleTask( final TaskContext context ) {
		// sanity check.
		if( context == null )
			throw new IllegalArgumentException( "context must not be null" );	
		final int N = 4;
		int tmpMin, tmpMinOld;
		tmpMin = tmpMinOld = executionUnit[0].getNumberOfEnqueuedTasks();
		int selectedEU = 0;
		for( int i = 1; i < N; ++i ) {		
			tmpMin = executionUnit[i].getNumberOfEnqueuedTasks();
			if( tmpMin < tmpMinOld ) {
				tmpMinOld = tmpMin;
				selectedEU = i;				
			}
		}			
		executionUnit[selectedEU].enqueueTask( context );
		LOG.info( "execute task " + context.task.name + " [" + context.task.uid + "]" 
				+ " on ExecutionUnit (" + executionUnit[selectedEU].getExecutionUnitID() + ")" );
	}	
}