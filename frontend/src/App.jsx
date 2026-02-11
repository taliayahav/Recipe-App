import React, { useEffect, useState } from 'react'
import axios from 'axios'

const API = import.meta.env.VITE_BACKEND_URL || 'http://localhost:7001'

export default function App() {
  const [recipes, setRecipes] = useState([])
  const [form, setForm] = useState({ title: '', ingredients: '', instructions: '' })
  const [editingId, setEditingId] = useState(null)
  const [view, setView] = useState('list') // 'list' or 'add'
  const [toast, setToast] = useState({ message: '', visible: false })

  useEffect(() => {
    fetchRecipes()
  }, [])

  async function fetchRecipes() {
    const res = await axios.get(`${API}/recipes`)
    setRecipes(res.data)
  }

  async function submit(e) {
    e.preventDefault()
    if (editingId) {
      await axios.put(`${API}/recipes/${editingId}`, form)
      setEditingId(null)
    } else {
      await axios.post(`${API}/recipes`, form)
    }
    setForm({ title: '', ingredients: '', instructions: '' })
    fetchRecipes()
  // show success and redirect to list view
  setToast({ message: editingId ? 'Recipe updated' : 'Recipe saved', visible: true })
  setView('list')
  }

  function startEdit(r) {
    setEditingId(r.id)
    setForm({ title: r.title || '', ingredients: r.ingredients || '', instructions: r.instructions || '' })
  setView('add')
  }

  async function remove(id) {
    if (!confirm('Delete this recipe?')) return
    await axios.delete(`${API}/recipes/${id}`)
    fetchRecipes()
  setToast({ message: 'Recipe deleted', visible: true })
  }

  useEffect(() => {
    let t
    if (toast.visible) {
      t = setTimeout(() => setToast({ ...toast, visible: false }), 3000)
    }
    return () => clearTimeout(t)
  }, [toast])

  return (
    <div className="container">
      <header className="nav">
        <h1 onClick={() => setView('list')} style={{cursor:'pointer'}}>My Recipes</h1>
        <div>
          <button onClick={() => { setView('list'); setEditingId(null) }} className={view==='list' ? 'active' : ''}>Home</button>
          <button onClick={() => { setView('add'); setEditingId(null) }} className={view==='add' ? 'active' : ''} style={{marginLeft:8}}>Add Recipe</button>
        </div>
      </header>

      {toast.visible && <div className="toast">{toast.message}</div>}

      {view === 'list' && (
        <div className="list">
          {recipes.map(r => (
            <div key={r.id} className="card">
              <div style={{display:'flex', justifyContent:'space-between'}}>
                <h3>{r.title}</h3>
                <div>
                  <button onClick={() => startEdit(r)}>Edit</button>
                  <button onClick={() => remove(r.id)} style={{marginLeft:6}}>Delete</button>
                </div>
              </div>
              <pre>{r.ingredients}</pre>
              <p>{r.instructions}</p>
            </div>
          ))}
        </div>
      )}

      {view === 'add' && (
        <div className="form">
          <h2>{editingId ? 'Edit Recipe' : 'Add Recipe'}</h2>
          <form onSubmit={submit}>
            <input placeholder="Title" value={form.title} onChange={e => setForm({...form, title: e.target.value})} required />
            <textarea placeholder="Ingredients (one per line)" value={form.ingredients} onChange={e => setForm({...form, ingredients: e.target.value})} />
            <textarea placeholder="Instructions" value={form.instructions} onChange={e => setForm({...form, instructions: e.target.value})} />
            <div style={{display:'flex', gap:8}}>
              <button type="submit">{editingId ? 'Update' : 'Save'}</button>
              <button type="button" onClick={() => { setEditingId(null); setForm({ title:'', ingredients:'', instructions:'' }); setView('list') }}>Cancel</button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}
