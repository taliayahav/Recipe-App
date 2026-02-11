import React, { useEffect, useState } from 'react'
import axios from 'axios'

const API = import.meta.env.VITE_BACKEND_URL || 'http://localhost:7001'

export default function App() {
  const [recipes, setRecipes] = useState([])
  const [form, setForm] = useState({ title: '', ingredients: '', instructions: '' })
  const [editingId, setEditingId] = useState(null)

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
  }

  function startEdit(r) {
    setEditingId(r.id)
    setForm({ title: r.title || '', ingredients: r.ingredients || '', instructions: r.instructions || '' })
  }

  async function remove(id) {
    if (!confirm('Delete this recipe?')) return
    await axios.delete(`${API}/recipes/${id}`)
    fetchRecipes()
  }

  return (
    <div className="container">
      <h1>My Recipes</h1>
      <div className="layout">
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
        <div className="form">
          <h2>Add Recipe</h2>
          <form onSubmit={submit}>
            <input placeholder="Title" value={form.title} onChange={e => setForm({...form, title: e.target.value})} required />
            <textarea placeholder="Ingredients (one per line)" value={form.ingredients} onChange={e => setForm({...form, ingredients: e.target.value})} />
            <textarea placeholder="Instructions" value={form.instructions} onChange={e => setForm({...form, instructions: e.target.value})} />
            <button type="submit">{editingId ? 'Update' : 'Save'}</button>
            {editingId && <button type="button" onClick={() => { setEditingId(null); setForm({ title:'', ingredients:'', instructions:'' }) }} style={{marginLeft:8}}>Cancel</button>}
          </form>
        </div>
      </div>
    </div>
  )
}
