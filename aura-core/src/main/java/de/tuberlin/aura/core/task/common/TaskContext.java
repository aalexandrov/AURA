package de.tuberlin.aura.core.task.common;

import java.util.ArrayList;
import java.util.List;

import de.tuberlin.aura.core.common.eventsystem.IEventDispatcher;
import de.tuberlin.aura.core.common.eventsystem.IEventHandler;
import de.tuberlin.aura.core.descriptors.Descriptors.TaskBindingDescriptor;
import de.tuberlin.aura.core.descriptors.Descriptors.TaskDescriptor;
import de.tuberlin.aura.core.task.common.TaskStateMachine.TaskState;
import de.tuberlin.aura.core.task.gates.InputGate;
import de.tuberlin.aura.core.task.gates.OutputGate;

/**
 * 
 */
public final class TaskContext {	
	
	public TaskContext( final TaskDescriptor task, 
						final TaskBindingDescriptor taskBinding,
						final IEventHandler handler, 
						final IEventDispatcher dispatcher, 
						final Class<? extends TaskInvokeable> invokeableClass ) {
		
		// sanity check.
		if( task == null )
			throw new IllegalArgumentException( "task == null" );	
		if( taskBinding == null )
			throw new IllegalArgumentException( "taskBinding == null" );
		if( handler == null )
			throw new IllegalArgumentException( "taskEventListener == null" );
		if( dispatcher == null )
			throw new IllegalArgumentException( "taskEventListener == null" );
		if( invokeableClass == null )
			throw new IllegalArgumentException( "invokeableClass == null" );
		
		this.task = task;
		
		this.taskBinding = taskBinding;
		
		this.handler = handler;
		
		this.dispatcher = dispatcher;
		
		this.state = TaskState.TASK_STATE_NOT_CONNECTED;
		
		this.invokeableClass = invokeableClass;
		
		if( taskBinding.inputGateBindings.size() > 0 ) {
			this.inputGates = new ArrayList<InputGate>( taskBinding.inputGateBindings.size() );
			for( final List<TaskDescriptor> inputGate : taskBinding.inputGateBindings ) 			
				inputGates.add( new InputGate( inputGate.size() ) ); 
		} else {
			this.inputGates = null;
		}
		
		if( taskBinding.outputGateBindings.size() > 0 ) {
			this.outputGates = new ArrayList<OutputGate>( taskBinding.outputGateBindings.size() );		
			for( final List<TaskDescriptor> outputGate : taskBinding.outputGateBindings ) 
				outputGates.add( new OutputGate( outputGate.size() ) ); 
		} else {
			this.outputGates = null;
		}
	}
	
	public final TaskDescriptor task;
	
	public final TaskBindingDescriptor taskBinding;
	
	public final IEventHandler handler;
	
	public final IEventDispatcher dispatcher; 
	
	public final Class<? extends TaskInvokeable> invokeableClass;
	
	public final List<InputGate> inputGates;
	
	public final List<OutputGate> outputGates;
	
	public TaskState state;
	
	@Override
	public String toString() {
		return (new StringBuilder())
				.append( "TaskContext = {" )
				.append( " task = " + task + ", " )
				.append( " taskBinding = " + taskBinding + ", " )
				.append( " state = " + state.toString() + ", " )				
				.append( " }" ).toString();
	}
}