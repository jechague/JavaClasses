/** $Log: SubmitThreadPool.java,v $
/** Revision 1.6  2007/11/20 19:29:50  iroqueta
/** El httpContext.getWebSession().invalidate() solo hay que hacerlo cuando el ambiente es web.
/**
/** Revision 1.5  2007/10/09 15:20:54  alevin
/** - Mejoras en la invalidaci�n de la session cuando para cuando se accede a la misma en un thread submit.
/**
/** Revision 1.4  2005/07/12 22:11:24  gusbro
/** - Hago copia de los parametros aunque haya una posicion libre en el pool de threads
/**
/** Revision 1.3  2005/03/29 19:58:07  gusbro
/** - Seteo nombre de los threads de submit
/**
/** Revision 1.2  2005/03/15 20:49:55  gusbro
/** - Arreglo problemas de deadlock y pasaje de parametros
/**
/** Revision 1.1  2003/11/04 20:29:06  gusbro
/** - Agrego pool de threads para submit en web
/**
 **/
package com.genexus.util;
import java.util.Vector;

import com.genexus.GXParameterPacker;
import com.genexus.GXParameterUnpacker;
import com.genexus.ISubmitteable;
import com.genexus.ModelContext;
import com.genexus.Preferences;
import com.genexus.db.SQLAndroidSQLiteHelper;

public class SubmitThreadPool
{
	public static final String SUBMIT_THREAD = "SubmitThread-";
	private static SubmitThread [] threadPool = null;
	private static Vector submitQueue = new Vector();
	private static int poolId = -1;
	private static int remainingSubmits = 0;
	private static GXParameterPacker parameterPacker;
	
	public synchronized static void submit(final ISubmitteable proc, final int id, final Object []submitParms)
	{
		if(threadPool == null)
		{ // Si el pool todav�a no fue creado
			threadPool = new SubmitThread[Preferences.getDefaultPreferences().getSUBMIT_POOL_SIZE()];
			parameterPacker = new GXParameterPacker();
		}
		
		SubmitThreadPool.incRemainingSubmits();
		if(threadPool.length == 0)
		{ // Si en la preference se puso 0 como cantidad del poolSize esto se considera como 'unlimited'
		  // En ese caso levantamos un thread por cada submit y no utilizamos el pool
			new Thread(new Runnable()
			{
				public void run()
				{
                    com.genexus.internet.HttpContext httpContext = ModelContext.getModelContext().getHttpContext();
					
                    SQLAndroidSQLiteHelper.beginTransaction();
                    proc.submit(id, submitParms);
					SQLAndroidSQLiteHelper.endTransaction();
					
                    SubmitThreadPool.decRemainingSubmits();
				}
			}, SUBMIT_THREAD + id).start();
			return;			
		}
		
		// Debo serializar los parametros porque si x ej un parametro era un SDT se debe mantener
		// exacto el estado dado que el submit puede que se ejecute mucho mas adelante!, incluso
		// aunque se ejecute en el momento, para el caso de sdts el caller puede aun cambiar algun
		// dato y tampoco queremos eso
		parameterPacker.reset();
		parameterPacker.writeObject(submitParms); 
		
		// Ahora busco un thread libre
		for(int i = 0; i < threadPool.length; i++)
		{
			if(threadPool[i] == null)
			{ // Si nunca se ha utilizado este thread, debo crearlo
				threadPool[i] = new SubmitThread(i);
				threadPool[i].start();
				
				// @HACK: al levantar al thread, debo esperar hasta que llegue al wait
				// porque sino se puede perder el primer notify() (y en efecto era lo que pasaba)				
				while(!threadPool[i].isInitialized())
				{
					try
					{
						Thread.sleep(100);
					}catch(InterruptedException e){ ; }
				}
			}
			
			synchronized(threadPool[i])
			{			
				if(threadPool[i].inUse())
				{ // Si el thread esta en uso, sigo buscando
					continue;
				}
				threadPool[i].setProc(proc, id, (Object[])new GXParameterUnpacker(parameterPacker.toByteArray()).readObject());				
				threadPool[i].notify();
				return;
			}
		}
		
		// Si llego aqui es porque tengo utilizados todos los thread, asi que encolo el submit
		submitQueue.addElement(new Object[]{proc, new Integer(id), parameterPacker.toByteArray()});
	}
	
	protected synchronized static void incRemainingSubmits()
	{
		remainingSubmits++;
	}
	
	protected synchronized static void decRemainingSubmits()
	{
		remainingSubmits--;
		SubmitThreadPool.class.notify();
	}
	
	public synchronized static void waitForEnd()
	{
		if(remainingSubmits > 0)
		{
			System.err.println("Waiting for " + remainingSubmits + " submitted procs to end...");
		}
		
		while(remainingSubmits > 0)
		{
			try
			{
				SubmitThreadPool.class.wait();
			}catch(InterruptedException e)
			{ 
				e.printStackTrace(); 
			}
		}
		destroyPool();
	}
	
	public synchronized static void destroyPool()
	{
		if(threadPool != null)
		{
			for(int i = 0; i < threadPool.length; i++)
			{
				if(threadPool[i] != null)
				{
					synchronized(threadPool[i])
					{
						threadPool[i].setExit();
						threadPool[i].notify();
						threadPool[i] = null;
					}
				}
			}
		}
		threadPool = null;
	}
	
	protected synchronized static Object [] getNextSubmit()
	{
		if(submitQueue.size() > 0)
		{
			Object [] nextSubmit = (Object[])submitQueue.firstElement();
			submitQueue.removeElement(nextSubmit);
			return nextSubmit;
		}
		return null;
	}			
}

class SubmitThread extends Thread
{
	private boolean exit = false;
	private Object threadLock;
	private boolean inUse = false;
	private ISubmitteable proc;
	private int submitId;
	private Object [] submitParms;
	private boolean initialized = false;
	
	public SubmitThread(int index)
	{
		super(SubmitThreadPool.SUBMIT_THREAD + index);
	}
	
	public void run()
	{		
            com.genexus.internet.HttpContext httpContext = ModelContext.getModelContext().getHttpContext();

		synchronized(this)
		{
			initialized = true;
		}
		while(!exit)
		{
			synchronized(this)
			{
				if(!inUse)
				{
					try
					{
						this.wait();
					}catch(InterruptedException e) { ; }
					if(exit)
					{
						break;
					}
				}
			}
				
			// Ejecuto el submit
			try
			{
		          SQLAndroidSQLiteHelper.beginTransaction();
                  proc.submit(submitId, submitParms);
			      SQLAndroidSQLiteHelper.endTransaction();
			
			}catch(Throwable e)
			{
				e.printStackTrace();
			}
				
			proc = null;
			submitParms = null;
			SubmitThreadPool.decRemainingSubmits();			
			
			// Veo si hay un nuevo submit para ejecutar
			Object [] nextSubmit = SubmitThreadPool.getNextSubmit();
			synchronized(this)
			{ // Aqui debo sincronizar pues se setea la variable inUse
				if(nextSubmit != null)
				{
					setProc((ISubmitteable)nextSubmit[0], ((Integer)nextSubmit[1]).intValue(), (Object[])new GXParameterUnpacker((byte[])nextSubmit[2]).readObject());
				}
				else
				{
					inUse = false;
				}			
			}
		}
	}
	
	public synchronized boolean inUse()
	{
		return inUse;
	}
	
	public synchronized void setProc(ISubmitteable proc, int submitId, Object [] submitParms)
	{
		this.proc = proc;
		this.submitId = submitId;
		this.submitParms = submitParms;
		inUse = true;
	}
	
	public synchronized void setExit()
	{
		this.exit = true;
	}
	
	public synchronized boolean isInitialized()
	{
		return initialized;
	}
}	
